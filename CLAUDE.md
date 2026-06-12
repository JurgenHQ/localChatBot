# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**LocalChatBot** is a Kotlin Multiplatform + Compose Multiplatform app (Android, iOS, Desktop) for chatting with a local LLM exposed via an OpenAI-compatible endpoint (LM Studio, llama.cpp, Ollama). The UI is 100% shared across all three platforms from `commonMain`.

## Build commands

```bash
# Compile Android
./gradlew :composeApp:compileDebugKotlinAndroid

# Build debug APK
./gradlew :composeApp:assembleDebug
# Output: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Install on connected Android device
./gradlew :composeApp:installDebug

# Compile iOS simulator
./gradlew :composeApp:compileKotlinIosSimulatorArm64

# Link iOS framework
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# Run Desktop app
./gradlew :composeApp:run

# Package Desktop distributions (DMG / MSI / DEB)
./gradlew :composeApp:packageDistributionForCurrentOS

# Android lint
./gradlew :composeApp:lintDebug

# Clean
./gradlew clean
```

There are no automated tests in this project.

## Architecture

### Layer structure

```
Presentation (Compose + ViewModels)
       ↓
   Domain (models, interfaces, use cases)
       ↑ implemented by
     Data (OpenAI DTOs, Ktor, repo impls)
       ↑ uses
     Core (theme, network, storage, platform expects)
```

- **`core/`** — cross-cutting infrastructure with no business logic: `HttpClientFactory`, `SettingsFactory`, `ActiveSessionStore` (shared ViewModel state), `StreamingStateStore`, platform `expect`/`actual` declarations (image decode/save, URL opener, TTS, speech recognition, system bars).
- **`domain/`** — pure models (`ChatSession`, `ChatMessage`, `AppPreferences`, `ConnectionConfig`), repository interfaces, use cases, and the `Tool` / `ToolRegistry` abstractions. Has zero dependency on Ktor or Compose.
- **`data/`** — Ktor-based API clients (`OpenAiApi`, `TavilyApi`, `ImageGenApi`, `DiagramRenderApi`, `LmStudioApi`), JSON DTOs, and `*RepositoryImpl` classes that persist via `multiplatform-settings`.
- **`presentation/`** — Compose screens, ViewModels, and Atomic Design components.
- **`di/AppContainer.kt`** — manual DI composition root; wires everything together. No framework.

### ViewModel per screen

| Screen | ViewModel |
|---|---|
| `OnboardingScreen` | `OnboardingViewModel` |
| `ChatScreen` | `ChatViewModel` |
| `SessionDrawer` | `SessionsViewModel` |
| `SettingsScreen` | `SettingsViewModel` |
| `SettingsEditorSheet` | `SettingsEditorViewModel` |
| `SkillsScreen` | `SkillsViewModel` |

Each screen has a `*Content(state, callbacks)` stateless composable for use in `@Preview`. The `NetworkInspectorScreen` is the only feature without a ViewModel (see below).

### Shared state between ViewModels

- `ChatRepository.sessions: Flow<List<ChatSession>>` — single source of truth for sessions.
- `ActiveSessionStore.activeSessionId: Flow<String?>` — written by the drawer on selection; read by `ChatViewModel`.
- `PreferencesRepository.preferences: Flow<AppPreferences>` — settings propagate reactively to all screens.

### Tool-calling loop (`UseCases.kt`)

`SendMessageUseCase` drives the multi-round loop:
1. Build the system prompt: user system text + optional skills index (`buildSkillsIndex` lists enabled skills so the model knows to call `use_skill`) + agent tool prompt.
2. Stream `/v1/chat/completions` with tool definitions.
3. If `tool_calls` arrive, execute each tool (web search, image generation, diagram render, filesystem/shell, `use_skill`, skill scripts). Destructive/confirmable tools route through `ToolConfirmationController` first (see below).
4. Push results as `role=tool` messages and re-stream (max `MAX_TOOL_ITERATIONS` rounds, currently 200).
5. Drain any out-of-band image produced and attach it to the final `ChatMessage` without sending base64 to the model.
6. After the first user→assistant exchange of a session, fire-and-forget a cheap non-streaming completion that generates the session title (replaces the first-40-chars placeholder).

### Human-in-the-loop tool confirmation

`ToolConfirmationController` (`core/confirm/`) coordinates approval between tools (data layer) and the UI. Tools with `requiresConfirmation` call `requestApproval(title, detail, force)`, which publishes a `PendingConfirmation` to a `StateFlow` and suspends until the UI resolves it. When `AppPreferences.fsYoloMode` is on, approval returns immediately without a dialog — except when `force = true` (used by `run_command` when the command matches the destructive-pattern denylist), which always shows the dialog even in YOLO.

### Available tools

| Tool | Requires | Notes |
|---|---|---|
| `search_web` | Tavily API key | HTTP call to Tavily; results shown as source chips |
| `generate_image` | Image Service at `:8080` | SDXL via ComfyUI; image returned out-of-band |
| `render_diagram` | Image Service at `:8080` | Mermaid → PNG via mermaid-cli + headless Chromium |
| `read_file` / `create_file` / `edit_file` / `delete_file` / `list_directory` / `create_directory` | Desktop only | Filesystem agent tools; `edit_file` does exact-string replacement (old string must be unique unless `replace_all`) |
| `run_command` | Desktop only | Shell execution tool (foreground with timeout, or background with PID); destructive patterns force a confirmation dialog even in YOLO |
| `manage_todos` | — | Session-scoped to-do list the model uses to plan multi-step tasks; shown in `TodoProgressPanel` |
| `use_skill` | — | Loads the full instructions for an installed skill on demand (the skills index in the system prompt only lists each skill's short description) |
| `sk_<skillId>_<scriptName>` | Desktop only | Custom per-skill shell scripts (`SkillScript`), built dynamically by `ScriptToolFactory`; each runs through the confirmation controller |
| `mcp_<serverId>_<toolName>` | MCP server (HTTP) | MCP server tools, built dynamically by `McpToolProvider`; each runs through the confirmation controller |

### MCP (Model Context Protocol)

Connects external MCP servers (HTTP / Streamable HTTP transport only) so the model can invoke their tools via the standard JSON-RPC 2.0 protocol. Works on all platforms — no local process spawning.

- **`McpServerConfig`** (`domain/model/McpServerConfig.kt`) — flat data class: `id`, `name`, `url`, `headers` (for auth, e.g. `Authorization: Bearer …`), `enabled`. Persisted via `PreferencesRepository` (JSON in settings, key `mcp_servers`).
- **`McpClient`** (`data/mcp/`) — orchestrates `initialize` → `notifications/initialized` → `tools/list` → `tools/call`. `initialize` params are built by hand and always include `capabilities: {}` (omitting it makes spec-strict servers reject the request). Timeouts: 10 s for init/list, 30 s per call.
- **`HttpMcpTransport`** (`data/mcp/`, commonMain Ktor) — full Streamable HTTP support: sends `Accept: application/json, text/event-stream`, captures the `Mcp-Session-Id` from `initialize` and resends it, parses SSE responses (extracts the `data:` block matching the request id), and treats empty/202 bodies (notifications) gracefully. `McpTransportLayer.sendNotification` writes without awaiting a response.
- **`McpToolProvider`** (`data/mcp/`) — manages lazy client connections per enabled server (mutex-guarded). Merges MCP tool definitions into the send loop alongside scriptTools. `connectServer` propagates the real error (surfaced by `testConnection`); a failed server is skipped during send without breaking the rest. `closeAll()` is called from the desktop shutdown hook in `main.kt`.
- **`McpTool : Tool`** (`domain/tools/`) — name `mcp_<serverId>_<toolName>` (sanitized, same `[^a-zA-Z0-9_-]→_` rule as `sk_*`). `requiresConfirmation = true` → routes through `ToolConfirmationController`.
- **UI**: `McpServersScreen` / `McpServersViewModel` / `McpServerEditSheet` (`presentation/features/mcp/`). Entry from `SettingsScreen` via `onOpenMcpServers`. The edit sheet captures URL + a key-value headers editor. Shows connection `StatusDot` (Unknown/Connecting/Connected/Error) per server and discovered tool count after "test connection".
- Cap: 30 tools per server (`MAX_TOOLS_PER_SERVER`) to avoid bloating the context sent to the model.
- Network Inspector records MCP HTTP calls as `Kind.McpCall`.

### Skills

Reusable behavior packs the user can enable to specialize the model. A `SkillDefinition` carries a short `description` (for the index), a `fullDescription`, a `systemPromptAddition` (injected when loaded via `use_skill`), and optional `scripts` (shell commands surfaced as `sk_*` tools on Desktop).

- **`SkillCatalog`** (`domain/skill/`) holds the built-in skills; `allFor(customSkills)` and `byId(id, customSkills)` merge built-ins with user-created ones.
- **Custom & imported skills** are persisted on disk via `SkillFileStore` (`expect`/`actual` per platform under `core/storage/`); `importFromFolder` parses skill markdown folders. `PreferencesRepository` tracks which skills are installed/enabled (`InstalledSkill`).
- **UI**: `SkillsScreen` / `SkillsViewModel` (browse, toggle, import) and `SkillCreateSheet` (author a new skill); `SkillSuggestionPopup` surfaces matching skills in the composer. Opened from `SettingsScreen` via `onOpenSkills`.
- **`SkillsExport`** is the JSON shape for exporting/importing skill bundles.

### NetworkInspectorScreen (no ViewModel)

`NetworkInspector` is a plain singleton that holds a circular buffer of the last 50 HTTP transactions. All API classes call `inspector.record(...)`. The screen reads `inspector.entries` (a `StateFlow`) directly with local `remember` state for selection and search. This is intentional — there are no side effects or use cases, and a ViewModel would be unable to receive events from the data layer.

### Atomic Design for components

```
atoms/       → indivisible pieces (AppTextField, PrimaryButton, StatusDot, TypingIndicator)
molecules/   → small compositions (MessageBubble, SessionRow, ContextUsageBar, SourceChip)
organisms/   → complete UI blocks (ChatTopBar, AppBottomBar, ChatComposer)
features/    → screens + ViewModels
```

## Platform targets

The project has four source sets:
- `commonMain` — all business logic and shared UI
- `androidMain` — OkHttp engine, `ChatForegroundService` (keeps streams alive), `SharedPreferences`, native SpeechRecognizer/TTS
- `iosMain` — Darwin engine, `NSUserDefaults`, `SFSpeechRecognizer`/`AVSpeechSynthesizer`
- `desktopMain` — CIO engine, filesystem/shell agent tools, JVM-based settings, `kotlinx-coroutines-swing`, TTS via the OS engine (`say` / PowerShell `System.Speech` / `spd-say`·`espeak`)

**Read-aloud (TTS):** every assistant bubble has a speaker icon (`MessageBubble`) that reads the message via `TextToSpeech` (shared instance in `AppContainer`, also used by the voice mode). `ChatViewModel.speakMessage`/`stopSpeaking` drive it and expose `speakingMessageId`; markdown is stripped before speaking. Works on all platforms (desktop TTS shells out to the OS engine). Note: full voice *conversation* mode (mic) is still mobile-only (`PlatformCapabilities.voiceSupported`).

Desktop entry point: `composeApp/src/desktopMain/kotlin/com/localchatbot/main.kt` — the `main()` function, macOS transparent title bar setup, and window configuration.

## Key constraints

- **Cleartext HTTP** is intentionally enabled (`usesCleartextTraffic="true"` in `AndroidManifest.xml` and `NSAllowsLocalNetworking` in iOS `Info.plist`) to reach local LAN servers — do not remove these.
- **Gradle must stay at 8.9** — AGP 8.7.3 is incompatible with Gradle 9.x.
- **`-Xexpect-actual-classes`** compiler flag is required (set in `build.gradle.kts`) because `expect`/`actual` classes are used across all four targets.
- Images from `generate_image` / `render_diagram` are stored **out-of-band** in a `StateFlow`, never in the chat context sent to the model, to avoid inflating token counts with base64.
