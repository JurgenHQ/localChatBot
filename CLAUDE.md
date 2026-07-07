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

### Background resume (mobile stream interruption)

On iOS the OS suspends the app when it goes background (~30 s grace per trip via `beginBackgroundTask`, re-armed on each `DidEnterBackground` by `BackgroundExecutor.ios.kt`) and NSURLSession kills the streaming socket — no `UIBackgroundModes` can keep an SSE stream alive. Instead of surfacing a raw network error, the retry loop in `SendMessageUseCase` detects *background interference*: `AppLifecycle` (`core/lifecycle/`, `expect`/`actual`) exposes `isForeground` plus a monotonic `backgroundCount` snapshotted before each stream attempt (the error may be delivered only after returning to foreground). Background-caused failures don't consume the normal retry budget (`STREAM_MAX_RETRIES`); the loop rolls back the partial assistant message (before parking, so a kill-while-suspended leaves clean persisted state), suspends in `awaitForeground()` (cancelable by user stop), and re-streams the current round on return — capped at `BACKGROUND_RESUME_MAX` resumes per turn. `isTransientNetworkError` (`core/network/TransientErrors.kt`) also matches the iOS suspension errors ("connection was lost" -1005, "connection abort" errno 53), and terminal failures are shown via `friendlyStreamErrorMessage` instead of raw engine text. Android is normally unaffected (`ChatForegroundService` keeps the socket); Desktop's `AppLifecycle` is constant-foreground (branch dead).

### Human-in-the-loop tool confirmation

`ToolConfirmationController` (`core/confirm/`) coordinates approval between tools (data layer) and the UI. Tools with `requiresConfirmation` call `requestApproval(title, detail, force)`, which publishes a `PendingConfirmation` to a `StateFlow` and suspends until the UI resolves it. When `AppPreferences.fsYoloMode` is on, approval returns immediately without a dialog — except when `force = true` (used by `run_command` when the command matches the destructive-pattern denylist), which always shows the dialog even in YOLO.

### Plan / Build mode

`AppPreferences.agentMode` (`AgentMode.Plan` | `AgentMode.Build`, default **Build**) gates whether the agent can mutate the project. In **Plan** mode the project-mutating tools (`create_file`, `edit_file`, `multi_edit`, `delete_file`, `create_directory`, `save_image`) report `isAvailable=false` via `FsToolUtil.isWriteAvailable` (= `isAvailable` && mode==Build) and are **not sent to the model** — it physically can't call them. `run_command` stays available (the agent prompt instructs Plan mode to use it read-only). `buildAgentPrompt` prepends a PLAN-MODE block telling the model to investigate and propose a plan, then ask the user to switch to Build to apply it. Toggled from the `AgentControlsBar` chip (Plan/Build), persisted via `PreferencesRepository.updateAgentMode` (key `agent_mode`). Read tools (`read_file`, `list_directory`) and non-fs tools are unaffected.

### Per-turn checkpoints (agent undo)

Before a file-mutating tool runs, `SendMessageUseCase.executeCall()` snapshots the file's pre-turn state via `CheckpointStore` (`core/storage/`, `expect`/`actual`; real on desktop, no-op on mobile). Snapshots live in `~/.localchatbot/checkpoints/<sessionId>/<turnId>/` (`manifest.json` + `blobs/`), where `turnId` = the turn's user-message id. Covered tools: `create_file`, `edit_file`, `multi_edit`, `delete_file`, `create_directory`, `save_image` — mutations via `run_command`, MCP or skill scripts are NOT captured (documented in the revert dialog). Key behaviours:

- Snapshot is **idempotent per path within a turn** (the pre-turn state is what matters; benign under parallel tool execution). Recursive dir deletes snapshot the tree with caps (200 files / 20 MB, `partial` flag). A snapshot failure never breaks the tool (wrapped in `runCatching`).
- The first mutation of a turn tags the announcing assistant message with `ChatMessage.checkpointId` (via `ChatRepository.updateMessageCheckpoint`) and prunes old checkpoints (last 10 turns per session, by dir mtime).
- UI: messages with `checkpointId` render a "↩ Revertir cambios de este turno" chip (`MessageBubble` → `ChatMessageList` → `ChatScreen` → `ChatViewModel.requestRevert`); note `ChatMessageList`'s visibility filter explicitly keeps otherwise-empty announcer messages that carry a checkpoint. Confirming (`RevertTurnDialog`) restores files only — created files are deleted, edited/deleted files restored byte-for-byte, in reverse manifest order — **chat messages are kept**. Feedback goes through the existing `errorMessage` banner. `SessionsViewModel.deleteSession` also deletes the session's checkpoints.

### Available tools

| Tool | Requires | Notes |
|---|---|---|
| `search_web` | Tavily API key | HTTP call to Tavily; results shown as source chips |
| `generate_image` | Image Service at `:8080` | SDXL via ComfyUI; image returned out-of-band |
| `render_diagram` | Image Service at `:8080` | Mermaid → PNG via mermaid-cli + headless Chromium |
| `save_image` | Desktop only | Persists the last generated image (from `generate_image`/`render_diagram`) to a PNG in the workspace. *Peeks* the out-of-band image (doesn't consume it, so it still shows in chat), decodes base64, writes via `FilesystemAgent.writeBytes`; routes through `ToolConfirmationController`. Renders as a "Imagen guardada" `FileActionBubble` chip |
| `read_file` / `create_file` / `edit_file` / `delete_file` / `list_directory` / `create_directory` | Desktop only | Filesystem agent tools; `edit_file` does exact-string replacement (old string must be unique unless `replace_all`) |
| `search_files` | Desktop only | Native recursive grep over the workspace (`FilesystemAgent.searchFiles`, `Files.walk`). `pattern` is regex by default with automatic literal fallback if it doesn't compile (`mode` in the payload says which ran); case-insensitive by default; optional `path`, `file_glob`, `literal`, `case_sensitive`, `max_results` (≤500). Skips binaries, files >1MB and heavy dirs (.git, build, node_modules…). Returns `path:line: text` hits relative to the workspace so chat links open the editor at that line. Read-only → no confirmation, available in Plan mode |
| `run_command` | Desktop only | Shell execution tool (foreground with timeout, or background with PID); destructive patterns force a confirmation dialog even in YOLO |
| `manage_todos` | — | Session-scoped to-do list the model uses to plan multi-step tasks; shown in `TodoProgressPanel` |
| `use_skill` | — | Loads the full instructions for an installed skill on demand (the skills index in the system prompt only lists each skill's short description) |
| `read_tool_docs` | Desktop only | Lazily returns the tool guide (`~/.localchatbot/tools.md`). The model calls it when unsure how to use a tool or when one keeps failing; avoids bloating every request with tool docs. Default content shipped via `DEFAULT_TOOLS_MD` (`ToolDocsStore`), seeded to disk on first read; user can edit the file |
| `read_memory` / `save_memory` | Desktop only | User-preference memory (`~/.localchatbot/memory.md`). `save_memory` appends a durable preference (confirmable); `read_memory` returns the full file. A capped summary is also injected into every system prompt (see Memory below). Backed by `MemoryStore` |
| `sk_<skillId>_<scriptName>` | Desktop only | Custom per-skill shell scripts (`SkillScript`), built dynamically by `ScriptToolFactory`; each runs through the confirmation controller |
| `mcp_<serverId>_<toolName>` | MCP server (HTTP any platform, stdio desktop only) | MCP server tools, built dynamically by `McpToolProvider`; each runs through the confirmation controller |

### Text/document attachments (`FilePicker`)

`rememberFilePicker` (`core/fs/FilePicker.kt`, `@Composable expect`) opens a native file picker and returns plain text prepended as a fenced code block in the user's message (`AttachedTextFile`, same model regardless of source format). Available on all three platforms — Android and iOS wire the same "attach text" button in `ChatComposer` that used to be desktop-only. Extraction is extension-based (`.pdf`, `.docx`, else raw-text decode) and implemented per platform since there's no shared jvmMain source set for Android+Desktop to share code:

- **Desktop**: `JFileChooser`/`FileDialog` (macOS) → Apache PDFBox (`org.apache.pdfbox:pdfbox`) for `.pdf`, `java.util.zip` + DOM XML parse of `word/document.xml` for `.docx`.
- **Android**: SAF `ActivityResultContracts.OpenDocument()` → `pdfbox-android` (`com.tom-roush:pdfbox-android`, AWT-free port; needs `PDFBoxResourceLoader.init()` before first use) for `.pdf`, same zip+XML approach for `.docx`.
- **iOS**: `UIDocumentPickerViewController` (`.Import` mode, copies into the sandbox — no security-scoped URL handling needed) → native `PDFKit.PDFDocument` for `.pdf`. For `.docx` there's no built-in unzip API, so `FilePicker.ios.kt` parses the ZIP structure by hand (central directory → `word/document.xml`) and inflates with Foundation's `decompressedDataUsingAlgorithm` (raw deflate); text extraction mirrors the Android/Desktop `<w:p>`/`<w:t>` criteria via namespace-tolerant regex instead of DOM.

Legacy binary `.doc` is unsupported everywhere (`onError` callback → `ChatViewModel.attachTextFileError` → `errorMessage`, same UX as stream failures). `onResult`/`onError` are plain callbacks (not suspend), matching `rememberImagePicker`'s pattern rather than the old bridge-singleton pattern used for `VoicePermissionBridge`.

### Tool docs (`read_tool_docs` / `tools.md`)

On-demand tool guide so the model can recover from misuse without bloating every request. `ToolDocsStore` (`core/storage/`, `expect`/`actual`) backs `~/.localchatbot/tools.md` (sibling of `skills/`, desktop only); if the file is missing it's seeded from `DEFAULT_TOOLS_MD` on first read. The `read_tool_docs` tool returns the whole file. The agent prompt (desktop only) tells the model to call it when unsure or when a tool keeps failing, and `edit_file`'s no-match error points there too. The default content focuses on the non-obvious behaviour models tend to miss (e.g. `edit_file`'s whitespace-tolerant fallback, line-range Mode B, the nearby-region hint). Curated by us — the user can edit the file but that's not the expected flow.

**Deterministic `edit_file` recovery:** beyond the prompt pointer, every failed `edit_file` result carries a `recovery` field injected straight into the tool output (`EDIT_FILE_RECOVERY` in `ToolDocsStore`). The model always sees the recovery steps on the next turn without having to choose to call `read_tool_docs` — `EditFileTool` wraps `FsResult.Err` into `editErrorPayload`.

### Memory (`memory.md` / `read_memory` / `save_memory`)

Durable user-preference store so the model honors conventions across tasks (commit style, naming, tone, language, tooling). `MemoryStore` (`core/storage/`, `expect`/`actual`) backs `~/.localchatbot/memory.md` (sibling of `tools.md`, desktop only), seeded with `MEMORY_HEADER` on first write. Read-write by the model, unlike the curated `tools.md`. **Hybrid access**: `SendMessageUseCase.buildMemoryContext()` injects a `<user-memory>` block (capped at `MEMORY_INJECT_CAP` chars; truncated tail points to `read_memory`) into the stable region of every system prompt — placed before the volatile workspace block for KV-cache reuse — and `read_memory` returns the full file on demand. `save_memory` appends one preference per call and is confirmable (`requiresConfirmation = true`) so the user sees what's being remembered.

### MCP (Model Context Protocol)

Connects external MCP servers so the model can invoke their tools via the standard JSON-RPC 2.0 protocol. Two transports: **HTTP / Streamable HTTP** (all platforms) and **stdio** (desktop only — launches the server as a local process, the standard way to run `npx`/`uvx`-based MCP servers).

- **`McpServerConfig`** (`domain/model/McpServerConfig.kt`) — flat data class: `id`, `name`, `url`, `headers` (for auth, e.g. `Authorization: Bearer …`), `enabled`, plus stdio fields `transport` (`"http"` default | `"stdio"`; a String, not enum, so unknown future values don't break deserialization), `command`, `args`, `env`. All new fields have defaults → configs persisted before stdio existed still deserialize. Persisted via `PreferencesRepository` (JSON in settings, key `mcp_servers`).
- **`McpClient`** (`data/mcp/`) — orchestrates `initialize` → `notifications/initialized` → `tools/list` → `tools/call`. `initialize` params are built by hand and always include `capabilities: {}` (omitting it makes spec-strict servers reject the request). Timeouts: 10 s for init/list, 30 s per call.
- **`HttpMcpTransport`** (`data/mcp/`, commonMain Ktor) — full Streamable HTTP support: sends `Accept: application/json, text/event-stream`, captures the `Mcp-Session-Id` from `initialize` and resends it, parses SSE responses (extracts the `data:` block matching the request id), and treats empty/202 bodies (notifications) gracefully. `McpTransportLayer.sendNotification` writes without awaiting a response.
- **`StdioMcpTransport`** (`desktopMain/data/mcp/`, behind `expect fun createStdioMcpTransport` in commonMain whose mobile actuals return null) — spawns the server via the user's login shell (`$SHELL -l -c "exec <cmd>"`, no `-i`: interactive rc output would corrupt the line-delimited framing; the `exec` makes `destroy()` kill the real server, and the login shell inherits nvm/homebrew PATH for `npx`/`uvx`). Speaks newline-delimited JSON-RPC over stdin/stdout; responses correlate by `id` against `CompletableDeferred`s; non-JSON stdout lines (servers that log to stdout) are ignored; stderr is drained as capped log. Process death fails all pending requests; `close()` does `destroy()` → 2 s grace → `destroyForcibly()`, wired into the existing `closeAll()` shutdown hook. Timeouts stay in `McpClient`.
- **`McpToolProvider`** (`data/mcp/`) — manages lazy client connections per enabled server (mutex-guarded). Picks the transport per `McpServerConfig.transport` (stdio config on mobile → clear "desktop only" error). Merges MCP tool definitions into the send loop alongside scriptTools. `connectServer` propagates the real error (surfaced by `testConnection`); a failed server is skipped during send without breaking the rest. `closeAll()` is called from the desktop shutdown hook in `main.kt`.
- **`McpTool : Tool`** (`domain/tools/`) — name `mcp_<serverId>_<toolName>` (sanitized, same `[^a-zA-Z0-9_-]→_` rule as `sk_*`). `requiresConfirmation = true` → routes through `ToolConfirmationController`.
- **UI**: `McpServersScreen` / `McpServersViewModel` / `McpServerEditSheet` (`presentation/features/mcp/`). Entry from `SettingsScreen` via `onOpenMcpServers`. The edit sheet has an HTTP|Stdio transport selector (stdio chips only on desktop): HTTP shows URL + key-value headers editor; stdio shows command, args (whitespace-split, quotes not supported) and env editor. Shows connection `StatusDot` (Unknown/Connecting/Connected/Error) per server and discovered tool count after "test connection"; stdio rows show `stdio · <command>` (or "solo desktop" on mobile).
- Cap: 30 tools per server (`MAX_TOOLS_PER_SERVER`) to avoid bloating the context sent to the model.
- Network Inspector records MCP calls (HTTP and stdio, `url="stdio://<command>"`) as `Kind.McpCall`.

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

## Interaction guidelines

- When you need the user to make a decision before proceeding, use the `AskUserQuestion` tool to present options. Do **not** make any other tool calls in the same response — wait for the user's answer first.
- If a question requires free-form input rather than discrete options, ask it in text and **stop** — do not continue with tool calls in that same turn.

## Key constraints

- **Cleartext HTTP** is intentionally enabled (`usesCleartextTraffic="true"` in `AndroidManifest.xml` and `NSAllowsLocalNetworking` in iOS `Info.plist`) to reach local LAN servers — do not remove these.
- **Gradle must stay at 8.9** — AGP 8.7.3 is incompatible with Gradle 9.x.
- **`-Xexpect-actual-classes`** compiler flag is required (set in `build.gradle.kts`) because `expect`/`actual` classes are used across all four targets.
- Images from `generate_image` / `render_diagram` are stored **out-of-band** in a `StateFlow`, never in the chat context sent to the model, to avoid inflating token counts with base64.
