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

Each screen has a `*Content(state, callbacks)` stateless composable for use in `@Preview`. The `NetworkInspectorScreen` is the only feature without a ViewModel (see below).

### Shared state between ViewModels

- `ChatRepository.sessions: Flow<List<ChatSession>>` — single source of truth for sessions.
- `ActiveSessionStore.activeSessionId: Flow<String?>` — written by the drawer on selection; read by `ChatViewModel`.
- `PreferencesRepository.preferences: Flow<AppPreferences>` — settings propagate reactively to all screens.

### Tool-calling loop (`UseCases.kt`)

`SendMessageUseCase` drives the multi-round loop:
1. Stream `/v1/chat/completions` with tool definitions.
2. If `tool_calls` arrive, execute each tool (web search, image generation, diagram render).
3. Push results as `role=tool` messages and re-stream (max `MAX_TOOL_ITERATIONS` rounds, currently 200).
4. Drain any out-of-band image produced and attach it to the final `ChatMessage` without sending base64 to the model.
5. After the first user→assistant exchange of a session, fire-and-forget a cheap non-streaming completion that generates the session title (replaces the first-40-chars placeholder).

### Available tools

| Tool | Requires | Notes |
|---|---|---|
| `search_web` | Tavily API key | HTTP call to Tavily; results shown as source chips |
| `generate_image` | Image Service at `:8080` | SDXL via ComfyUI; image returned out-of-band |
| `render_diagram` | Image Service at `:8080` | Mermaid → PNG via mermaid-cli + headless Chromium |
| `read_file` / `create_file` / `edit_file` / `delete_file` / `list_directory` / `create_directory` | Desktop only | Filesystem agent tools; `edit_file` does exact-string replacement (old string must be unique unless `replace_all`) |
| `run_command` | Desktop only | Shell execution tool (foreground with timeout, or background with PID) |
| `manage_todos` | — | Session-scoped to-do list the model uses to plan multi-step tasks; shown in `TodoProgressPanel` |

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
- `desktopMain` — CIO engine, filesystem/shell agent tools, JVM-based settings, `kotlinx-coroutines-swing`

Desktop entry point: `composeApp/src/desktopMain/kotlin/com/localchatbot/main.kt` — the `main()` function, macOS transparent title bar setup, and window configuration.

## Key constraints

- **Cleartext HTTP** is intentionally enabled (`usesCleartextTraffic="true"` in `AndroidManifest.xml` and `NSAllowsLocalNetworking` in iOS `Info.plist`) to reach local LAN servers — do not remove these.
- **Gradle must stay at 8.9** — AGP 8.7.3 is incompatible with Gradle 9.x.
- **`-Xexpect-actual-classes`** compiler flag is required (set in `build.gradle.kts`) because `expect`/`actual` classes are used across all four targets.
- Images from `generate_image` / `render_diagram` are stored **out-of-band** in a `StateFlow`, never in the chat context sent to the model, to avoid inflating token counts with base64.
