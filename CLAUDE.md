# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**LocalChatBot** is a Kotlin Multiplatform + Compose Multiplatform app (Android, iOS, Desktop) for chatting with a local LLM exposed via an OpenAI-compatible endpoint (LM Studio, llama.cpp, Ollama). The UI is 100% shared across all three platforms from `commonMain`. On Desktop it is also a full coding agent (filesystem/shell tools, MCP, skills, scheduled tasks, remote access).

> `README.md` and `ARCHITECTURE.md` predate the Desktop target and the agent features (they describe a mobile-only chat app persisting to `multiplatform-settings`). Treat **this file** as the authoritative architecture reference; the README is still accurate for the *server-side* setup (LM Studio, ComfyUI, the FastAPI image/diagram service).

## Build commands

```bash
# Compile Android
./gradlew :composeApp:compileDebugKotlinAndroid

# Compile Desktop (fastest smoke check for commonMain changes)
./gradlew :composeApp:compileKotlinDesktop

# Compile iOS simulator
./gradlew :composeApp:compileKotlinIosSimulatorArm64

# Run Desktop app
./gradlew :composeApp:run

# Build debug APK  → composeApp/build/outputs/apk/debug/composeApp-debug.apk
./gradlew :composeApp:assembleDebug

# Install on connected Android device
./gradlew :composeApp:installDebug

# Link iOS framework
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# Package Desktop distributions (DMG / MSI / DEB for the current OS)
./gradlew :composeApp:packageDistributionForCurrentOS
./gradlew :composeApp:packageMsi          # what CI runs on windows-latest

# Android lint
./gradlew :composeApp:lintDebug

# Clean
./gradlew clean
```

There are no automated tests in this project. Changes touching `commonMain` should at minimum compile for Desktop **and** one mobile target (`expect`/`actual` gaps only surface per target).

CI (`.github/workflows/windows-build.yml`) builds the MSI on every push to `main` and `feature/**`: `main` publishes a rolling `latest` pre-release, feature branches upload a 30-day artifact.

## Architecture

### Layer structure

```
Presentation (Compose + ViewModels)
       ↓
   Domain (models, interfaces, use cases)
       ↑ implemented by
     Data (OpenAI DTOs, Ktor, SQLDelight, repo impls)
       ↑ uses
     Core (theme, network, storage, platform expects)
```

- **`core/`** — cross-cutting infrastructure with no business logic: `HttpClientFactory`, `SettingsFactory`, the shared state stores (`ActiveSessionStore`, `ActiveWorkspaceStore`, `StreamingStateStore`, `PendingUserPromptStore`), `ToolConfirmationController`, `AutomationScheduler`, `RemoteAccessServer`, and the platform `expect`/`actual` declarations (image decode/save, URL opener, TTS, speech recognition, system bars, filesystem agent, notifications, checkpoints).
- **`domain/`** — pure models (`ChatSession`, `ChatMessage`, `AppPreferences`, `ConnectionConfig`, `Project`, `ScheduledTask`), repository interfaces, use cases, and the `Tool` / `ToolRegistry` abstractions. Has zero dependency on Ktor or Compose.
- **`data/`** — Ktor-based API clients (`OpenAiApi`, `TavilyApi`, `ImageGenApi`, `VideoGenApi`, `DiagramRenderApi`, `LmStudioApi`), MCP clients/transports, JSON DTOs, and `*RepositoryImpl` classes (chat → SQLDelight, everything else → `multiplatform-settings`).
- **`presentation/`** — Compose screens, ViewModels, and Atomic Design components.
- **`di/AppContainer.kt`** — manual DI composition root; wires everything together. No framework. Read this first when tracing how a feature is assembled.

### Persistence: SQLDelight for chat, settings for the rest

Chat history lives in **SQLite via SQLDelight** (`commonMain/sqldelight/com/localchatbot/data/local/db/Session.sq` + `Message.sq`, package `com.localchatbot.data.local.db`, `verifyMigrations = true`). Everything else (preferences, profiles, skills, MCP servers, projects, scheduled tasks) still lives in `multiplatform-settings` as JSON blobs.

- **`ChatRepository` is split by cost, not by entity.** There is deliberately no `sessions: Flow<List<ChatSession>>` — that flow rebuilt the entire domain graph (all messages of all sessions, JSON column adapters deserializing every row) on *every* write to either table, including each 120 ms streaming flush. Three members replace it:
  - `sessionSummaries: Flow<List<SessionSummary>>` — `selectAllSessionSummaries`, metadata only, for the drawer and the remote-access session list. `SessionSummary` has no `messages`, so those screens *cannot* accidentally depend on the history again. `lastMessagePreview` is resolved by a correlated subquery inside SQLite (last non-`Tool` message), not derived from a message list in memory — that derivation was what tied the drawer to the whole history. It is **not** combined with `mediaOverlay`: the drawer shows no images, so generating one must not re-emit the list.
  - `sessionWithMessages(sessionId): Flow<ChatSession?>` — `selectSessionById` + `selectMessagesBySession` + `mediaOverlay`. Collected only for the **active** session (`ChatViewModel` and the remote-access snapshot both reach it via `flatMapLatest` over `activeSessionId`), so streaming only deserializes what is on screen.
  - `messageImageDataUrl(messageId)` — the remote `/image` endpoint used to scan every session looking for the message. Images live *only* in `mediaOverlay`, so this is a map lookup.
- Two traps in `selectAllSessionSummaries`, both documented in `Session.sq`: the preview subquery is wrapped in **`NULLIF(…, '')` purely to force nullability** (SQLDelight infers the type from `message.content NOT NULL` and generates `getString(n)!!`, but a subquery with no rows returns NULL → NPE on an empty session), and the role filter is a **bind parameter** rather than the literal `'Tool'` because the column is adapted to `Role`. The subquery depends on `message_session_sort_idx (session_id, sort_order)` to be a seek instead of a per-session scan+sort; see the driver note below.
- **`imageDataUrl` / `videoDataUrl` are deliberately not persisted** — they're transient base64 held only in `mediaOverlay`. Keep it that way if you refactor.
- `flushPendingWrites()` is a no-op on the SQLDelight impl (writes are transactional and immediate); it only mattered for the legacy throttled-JSON repo, and the desktop shutdown hook still calls it.
- **One-shot migration**: `AppContainer.init` runs `ChatHistoryMigration` *blocking* (`runBlocking`) before exposing `chatRepository`, guarded by the settings flag `chat_migrated_to_sqldelight_v1`, after `backupSettingsBeforeChatMigration()`. It reads through `data/repository/legacy/ChatRepositoryLegacyImpl` (`LegacySettingsChatRepository`), which exists **only** as the migration source — don't reintroduce it as a live repo. It is **not** a `ChatRepository` any more: implementing the whole contract dragged ~330 lines of write path (mutations, persistence throttle, derived flows) that nobody called, and forced a new member every time the interface changed. It is now just `load()` plus the one-key→per-session-key normalisation it needs to read the oldest format. The migration is idempotent: "SQLite already has sessions" is treated as "already migrated" so a lost flag can't cause PK violations.
- Platform drivers: `sqldelight-android-driver` / `native-driver` / `sqlite-driver`, created by `createLocalChatBotDatabase` (`core/storage/`, `expect`/`actual`).
- **Schema migrations work now** (they didn't until roadmap 1.1 was closed). Android and iOS never had the problem: their drivers receive the `SqlSchema` and delegate to the engine's own `onCreate`/`onUpgrade`. The JDBC driver stamps nothing, and the desktop factory only called `Schema.create()` when the file was missing — so DDL added to a `.sq` reached **new databases only**, silently, on the primary platform. What closes it:
  - **`migrateOrCreate`** (`desktopMain/core/storage/db/DesktopSchemaMigration.kt`), called from `DatabaseDriverFactory.desktop.kt`: creates + stamps a new DB, or migrates an existing one from its `PRAGMA user_version` to `Schema.version`. It runs **before** `PRAGMA foreign_keys=ON` (SQLite's own advice: a migration that rebuilds a table would otherwise fire the cascades while emptying the original).
  - **Baseline adoption.** Every desktop DB created before this existed sits at `user_version = 0` even though its content *is* the v1 schema; migrating "from 0" would re-apply migrations it already has. A stored 0 is therefore adopted as version 1 — pragma only, not a single row touched. `ensureMessageSortIndex` runs *before* the stamp, not after, so the DB genuinely matches the v1 snapshot before being labelled as such.
  - **`databases/1.db`** (`schemaOutputDirectory` in `build.gradle.kts`) is the committed snapshot of v1 — i.e. what shipped to users. `verifyMigrations` applies the `.sqm` files to it and fails the build unless the result matches the `.sq` CREATE statements, so a migration that drifts from the schema can't merge. **To add a migration:** generate `<current>.db` via `generateCommonMainLocalChatBotDatabaseSchema` *before* editing the `.sq`, then write `<current>.sqm`. Note `ALTER TABLE ADD COLUMN` appends at the end, so a new column must be declared **last** in the `.sq` or verification fails on column order.
  - **A backup precedes every migration**: `localchatbot.db.v<from>.bak`, written with `VACUUM INTO` rather than a file copy — the DB is in WAL mode, so committed data may still live in the `-wal` and copying only the `.db` would lose exactly that. It is best-effort (`runCatching`): a full disk should not leave the app unusable. Separate from the daily `.bak`.
  - **The backup is desktop-only, and that is a known, accepted gap.** Android and iOS migrate through their drivers' own `onUpgrade` callbacks and never reach `migrateOrCreate`, so on mobile a migration runs with no safety net — which matters because `2.sqm` *deletes* rows. It was left this way deliberately (this project is used on desktop); if mobile ever becomes real usage, the fix is to snapshot the DB file before constructing the driver when `user_version` is behind `Schema.version`. Don't assume the backup exists when reasoning about a migration's blast radius.
  - **`VACUUM` runs after a migration**, outside it — `VACUUM` cannot execute inside a transaction and `Schema.migrate` is one. Deleting rows only marks pages reusable; without this, `2.sqm` would drop 94% of a real database's content and the file would keep its old size forever (measured: 12 MB → 1.2 MB). Best-effort, and desktop-only — the Android/iOS drivers run migrations through their own callbacks and never reach this code.
  - `ensureMessageSortIndex` (`core/storage/db/MessageSortIndex.kt`) stays, and is still called directly by the Android and iOS factories: the index predates versioning, so a DB adopted as v1 may not have it.
- **`message.model` (migration `1.sqm`) is the worked example.** Nullable, no default — in SQLite that `ADD COLUMN` is metadata-only and rewrites nothing, so existing rows keep their content and get `NULL`. Readers treat `NULL` as "unknown" and fall back to `ChatSession.model`, which is exactly what was displayed before. Written together with the metrics at end of turn (`ChatRepository.updateMessageMetrics(…, model)`), because the server-reported model isn't known until then and `session.model` gets overwritten whenever the user switches models.
- **Desktop settings writes are asynchronous.** `PropertiesSettings` invokes its `onModify` callback synchronously inside every `putX`, and nearly every preference write comes from a `viewModelScope.launch` — which on Desktop is `Dispatchers.Main.immediate`, i.e. the **Swing EDT**. Writing `settings.xml` there blocked the UI thread on each toggle (negligible on macOS, visible as click lag on Windows: NTFS + Defender scanning the `.tmp`, the `.bak` and the rename). `SettingsFactory.desktop.kt` now snapshots the `Properties` on the calling thread (under the `Hashtable` monitor, so a concurrent `putX` can't throw `ConcurrentModificationException`) and hands it to a single daemon writer thread, coalescing bursts to the newest snapshot. The trade-off is that a write can be lost if the process is killed within a few ms; the desktop shutdown hook calls `SettingsFactory.flushPendingWrites()` alongside `chatRepository.flushPendingWrites()`. **Anything that reads `settings.xml` from disk must not assume the last `putX` already landed.**

### ViewModel per screen

| Screen | ViewModel |
|---|---|
| `OnboardingScreen` | `OnboardingViewModel` |
| `ChatScreen` | `ChatViewModel` |
| `SessionDrawer` | `SessionsViewModel` |
| `AgentScreen` (bottom tab) | `AgentViewModel` |
| `SettingsScreen` | `SettingsViewModel` |
| `SettingsEditorSheet` | `SettingsEditorViewModel` |
| `SkillsScreen` | `SkillsViewModel` |
| `McpServersScreen` | `McpServersViewModel` |
| `TasksScreen` | `TasksViewModel` |
| `EditorScreen` | `EditorViewModel` |
| `ModelPickerSheet` | `ModelPickerViewModel` |
| `RemoteViewerScreen` | `RemoteViewerViewModel` |

Each screen has a `*Content(state, callbacks)` stateless composable for use in `@Preview`. The `NetworkInspectorScreen` is the only feature without a ViewModel (see below).

Navigation is `MainScaffold` (`presentation/navigation/`): three bottom tabs (`BottomTab.Chat | Agent | Settings`) plus full-screen overlays for Network Inspector, Skills, MCP servers, Editor, Remote viewer and Tasks. `App.kt` picks onboarding vs `MainScaffold` from `prefs.onboardingDone`.

### Shared state between ViewModels

- `ChatRepository.sessions: Flow<List<ChatSession>>` — single source of truth for sessions.
- `PreferencesRepository.preferences: Flow<AppPreferences>` — settings propagate reactively to all screens.
- `ProjectRepository.state: Flow<ProjectState>` — projects + `sessionId → projectId` assignments.
- `ActiveSessionStore.activeSessionId: Flow<String?>` — written by the drawer on selection; read by `ChatViewModel`. Also carries `lastUserImageDataUrl` (source image for the cartoon/animate tools).
- `ActiveWorkspaceStore` — derives the **effective workspace** and **effective agent mode** for the active session (see below). `AppContainer` binds it into `FsToolUtil.workspaceStore` so every fs tool resolves paths against it.
- `PendingUserPromptStore` — per-session question published by the `ask_user` tool, rendered by `ChatScreen`.
- `QueuedMessageStore` — per-session messages typed *while a turn was running*, sent **merged into one** when it ends. See Message queue below.
- `StreamingStateStore` — in-flight streams + current tool activity.

### Connection profiles

`AppPreferences.connectionProfiles: List<ConnectionProfile>` (**max 3**) + `activeConnectionProfileId`. `AppPreferences.connection` is a *derived* getter returning the active profile's `ConnectionConfig` — nothing reads a standalone connection field anymore. Switching profiles happens from the drawer switcher. `ConnectionConfig` supports host/port/HTTPS/model plus an optional `apiKey` sent as `Authorization: Bearer …` (so cloud providers work, not just LAN). `SettingsExport` keeps a deprecated nullable `connection` field purely to read pre-profiles backups.

### Projects and per-session workspace

`Project` (id, name, `workspaceDir`, `collapsed`) + `ProjectState.assignments` (`sessionId → projectId`), persisted by `ProjectRepositoryImpl` in settings. `ActiveWorkspaceStore` resolves, for the active session:

- **`effectiveWorkspace`** = assigned project's `workspaceDir`, else the global `prefs.fsWorkspaceDir` (orphan assignments fall back silently).
- **`effectiveAgentMode`** = `prefs.sessionAgentModes[sessionId]` override, else the global `prefs.agentMode`.

Both feed the fs tools (via `FsToolUtil.workspaceStore`) and the `<workspace>` block of the system prompt. Desktop-only in practice; with no projects and no overrides the behaviour is identical to the global-only setup. The drawer groups sessions under collapsible project sections.

### Tool-calling loop (`UseCases.kt`)

`SendMessageUseCase` drives the multi-round loop:
1. Build the system prompt: user system text + optional skills index (`buildSkillsIndex` lists enabled skills so the model knows to call `use_skill`) + agent tool prompt + `<user-memory>` + rolling context summary + `<workspace>` block (computed once per turn: cwd, file tree, git status, AGENTS.md/CLAUDE.md). Stable parts go first for KV-cache reuse; volatile parts last.
2. Stream `/v1/chat/completions` with the definitions of *available* tools only (unavailable tools are never sent).
3. If `tool_calls` arrive, execute each tool (web search, image/video generation, diagram render, filesystem/shell, `use_skill`, skill scripts, MCP). Destructive/confirmable tools route through `ToolConfirmationController` first.
4. Push results as `role=tool` messages and re-stream (max `MAX_TOOL_ITERATIONS` rounds, currently 200) — **unless** the tool sets `Tool.endsTurn` (only `ask_user`), which hands control back to the user; their reply arrives as the next `role=user` message.
5. Drain any out-of-band image/video produced and attach it to the final `ChatMessage` without sending base64 to the model.
6. After the first user→assistant exchange of a session, fire-and-forget a cheap non-streaming completion that generates the session title (replaces the first-40-chars placeholder).

Other behaviours worth knowing before touching this file: the *nudge* mechanism re-prompts the model with an ephemeral instruction (folded into the system message, never persisted) in three cases — it announced an action but emitted no `tool_call`, it ended the turn with unfinished todos (`MAX_TODO_NUDGES`), or it asked a question in prose instead of calling `ask_user` (below). Token metrics accumulate across rounds.

**Streaming writes are throttled.** Content and reasoning deltas accumulate in buffers and hit SQLite at most every `STREAM_PERSIST_INTERVAL_MS` (120 ms) via `maybePersist`/`flushStreamBuffers`, instead of one `UPDATE` per token — see the cost note under Persistence. The first delta of each round always writes (so text appears immediately), and the attempt's `finally` flushes whatever is left, under `NonCancellable`, so pressing stop keeps the text generated up to that moment. The retry/rollback path clears the dirty flags instead of flushing, since it deletes the partial message anyway.

**Forcing `ask_user`.** The prompt rule alone doesn't hold with local models: asked to run a questionnaire they dump the questions as text, the turn ends and nothing lets the user reply. So when the model closes a turn without tool calls, `looksLikeQuestionToUser(buffer)` checks whether the final text is a question addressed to the user (last non-empty line ends in `?`, or ≥2 lines do — conservative, to avoid rhetorical questions mid-explanation); if so, and `ask_user` wasn't called this turn, `ASK_USER_NUDGE` is injected and the round re-streams **once** (`MAX_ASK_USER_NUDGES`). The already-written text is **not** rolled back — a false positive costs one extra round-trip, never lost content — and the nudge never answers on the user's behalf. The nudge also tells the model to ask one question per call, since `ask_user` takes a single question.

**Rolling context summary.** When `buildMessagesForApi` has to drop old messages to fit the context window, it returns them as `discarded`; once per turn a background `model.summarize(...)` folds them into `ChatSession.contextSummary` (column `session.context_summary`), which is re-injected into the system prompt as "Resumen del historial anterior". The summary job is fired only on the first iteration of a turn, so the fresh value lands on the *next* turn.

### Background resume (mobile stream interruption)

On iOS the OS suspends the app when it goes background (~30 s grace per trip via `beginBackgroundTask`, re-armed on each `DidEnterBackground` by `BackgroundExecutor.ios.kt`) and NSURLSession kills the streaming socket — no `UIBackgroundModes` can keep an SSE stream alive. Instead of surfacing a raw network error, the retry loop in `SendMessageUseCase` detects *background interference*: `AppLifecycle` (`core/lifecycle/`, `expect`/`actual`) exposes `isForeground` plus a monotonic `backgroundCount` snapshotted before each stream attempt (the error may be delivered only after returning to foreground). Background-caused failures don't consume the normal retry budget (`STREAM_MAX_RETRIES`); the loop rolls back the partial assistant message (before parking, so a kill-while-suspended leaves clean persisted state), suspends in `awaitForeground()` (cancelable by user stop), and re-streams the current round on return — capped at `BACKGROUND_RESUME_MAX` resumes per turn. On resume the partial text is re-injected as an unterminated assistant message (`resumePrefix`) so llama.cpp/LM Studio/Ollama continue from it instead of regenerating. `isTransientNetworkError` (`core/network/TransientErrors.kt`) also matches the iOS suspension errors ("connection was lost" -1005, "connection abort" errno 53), and terminal failures are shown via `friendlyStreamErrorMessage` instead of raw engine text. Android is normally unaffected (`ChatForegroundService` keeps the socket); Desktop's `AppLifecycle` is constant-foreground (branch dead).

### Human-in-the-loop tool confirmation

`ToolConfirmationController` (`core/confirm/`) coordinates approval between tools (data layer) and the UI. Tools with `requiresConfirmation` call `requestApproval(title, detail, force)`, which publishes a `PendingConfirmation` to a `StateFlow` and suspends until the UI resolves it. When `AppPreferences.fsYoloMode` is on, approval returns immediately without a dialog — except when `force = true` (used by `run_command` when the command matches the destructive-pattern denylist), which always shows the dialog even in YOLO. `AutoApproveConfirmations` is a **coroutine-context marker** that makes `requestApproval` return true for everything (even denylist-forced dialogs); because it propagates down the turn's coroutine tree, it scopes auto-approval to the run that installs it — a scheduled task running unattended — without touching the interactive chat.

Two things the controller does beyond arbitration, both because a pending approval is the one point where the agent is fully blocked on a human:

- **It notifies.** `notifyPending` fires a `SystemNotifier` toast (`"Se necesita tu aprobación"` + the truncated detail) whenever a request actually becomes pending, gated on `desktopNotificationsEnabled`. `SystemNotifier` is a constructor dependency, so `AppContainer` **must declare `systemNotifier` before `toolConfirmationController`** — Kotlin initialises properties in order.
- **Its dialog is not owned by the chat.** `ToolConfirmationDialog` renders in `MainScaffold`, outside the `when (selected)`, so an approval request is visible from the Agent/Settings tabs too. It used to live inside `ChatScreen`, which meant a request raised while you were on another tab left the turn silently blocked.

`ask_user` is the *non-blocking* counterpart: it publishes to `PendingUserPromptStore` and ends the turn instead of suspending on a `CompletableDeferred`. Its panel does live in the chat composer, so `MainScaffold` switches back to the Chat tab (`LaunchedEffect` on the pending prompt) when a question arrives.

### Message queue (typing while the model works)

A turn can run for minutes (tool loop), so `send()` no longer drops the message when the session is streaming — it pushes the text to `QueuedMessageStore` (`core/state/`, per session, in memory, never persisted) and the queue is sent **merged into a single user message** when the turn ends. Design doc: `docs/superpowers/specs/2026-07-26-cola-mensajes-design.md`.

- **The drain lives *outside* the `finally` that calls `streamingStateStore.stop()`** in `ChatViewModel.startTurn`. Inside it, the session would still be marked streaming and the merged message would re-enqueue itself — an infinite loop. Being after the `try/finally` also means a cancelled turn (Stop) never reaches it, which is the wanted behaviour.
- `send()` and the drain share `startTurn(...)`, extracted so the queue path doesn't duplicate the stream launch. Its `allowCreateSession` flag is false for the queue: `send()` creates a session when the active one was deleted, but resurrecting a deleted conversation to dump old queued messages into it would be surprising, so the queue is discarded instead.
- **Auto-drain only after a turn that ends cleanly.** Not after Stop (you just halted the model), not after a failure (you should see the error first), and not when `ask_user` left a question open (the queued text was written before the question existed, so sending it as the answer would answer something else). In those cases `QueuedMessagesCard` shows an "Enviar ahora" button — the queue must never become unsendable dead state.
- `drain()` is atomic (`getAndUpdate`) because both the turn end and "Enviar ahora" can fire it.
- Queued items render as **one dashed container** (`QueuedMessagesCard`), not as separate ghost bubbles: they are sent merged, so N bubbles collapsing into one would misrepresent what happens.
- Text only — the attach button is disabled while streaming.

### Manual context compaction (`/compact`)

`CompactContextUseCase` + `CompactContextDialog`. The rolling summary that already existed is
automatic and reactive (it only runs once the window overflowed, invisibly); this is the
opposite: you trigger it, you see the summary, and you can edit it before it applies.

- **Two steps on purpose.** `preview()` generates the summary and touches nothing; `apply()`
  persists the (possibly hand-edited) text and sets the cut. Cancelling leaves no trace.
- **Applying does not delete messages.** They stay in SQLite and on screen; they simply stop
  being sent to the model, which sees `contextSummary` instead. That's the whole difference
  from truncating a conversation.
- **The cut lives in preferences**, `AppPreferences.sessionCompactBoundaries` (`sessionId →
  CompactBoundary`), not in a `session` column — decided when there was no working schema
  migration path (see Persistence; there is one now). Same pattern as `sessionAgentModes`.
  Not worth migrating for its own sake, but a new cut like it should now go in a column.
- `CompactBoundary` carries `appliedAtEpochMs`, and it is not decorative: `ContextUsageBar` reads
  the server-measured `metrics.contextTokens`, and without knowing *when* the cut was applied
  there is no way to tell a stale measurement (taken while the dropped messages were still being
  sent) from a post-cut one — the bar stayed frozen at the pre-compaction number. Now
  `computeContextTokens` only trusts measurements newer than the cut; until the next turn produces
  one it estimates what is actually sent (summary + post-cut messages) plus the fixed
  system+tools overhead, derived from the stale measurement as "what the server counted beyond
  the messages of that moment".
- `buildMessagesForApi` takes `compactedThroughId` and drops everything up to it *before*
  windowing, then `dropWhile { it.role == Role.Tool }` so the request can't start with orphan
  tool results. `truncated` is `discarded.isNotEmpty() || compacted`, or the summary wouldn't be
  injected in the compaction case (where nothing was discarded by the window).
- **A stale cut is self-healing**: if the id no longer exists (the user resent an earlier message
  and truncated the session) it's ignored and the full history is used. Degrading to "not
  compacted" is safe; under-sending would not be.
- `preview()` never compacts the last `KEEP_RECENT` (6) messages — summarizing the exchange in
  flight would leave the model working from a summary of what it just said. It also caps the
  transcript keeping **head and tail** (`ModelRepositoryImpl.summarize` truncates at 8k chars, and
  a head-only cut would summarize a long conversation from its beginning alone).
- Entry points: the `/` popup in the composer, the chat top-bar `⋮` menu, and the command palette.
  "Deshacer compactación" clears the cut. `SessionsViewModel.deleteSession` clears it too, or the
  entry would outlive its session in preferences forever.

### Composer slash commands

Typing `/` opens `SlashSuggestionPopup`, which lists **commands** (a `SlashCommand` enum:
`/compact`, `/descompactar`, `/exportar`, `/nueva`) and then the installed **skills**. Both are
invoked with `/` but differ in kind, hence the two sections: a command runs an app action and
sends nothing to the model; a skill modifies the *next* message by injecting its system prompt.

- `SlashCommand.availableFor(hasMessages, compacted)` hides what can't act right now — offering
  "compactar" with no conversation only produces an error the user didn't ask for.
- `SlashCommand.parse` matches by **exact equality** (plus aliases), never by prefix: `/caveman
  ultra` must reach the skill picker, not a command.
- Both invocation paths — picking from the popup and typing it + Enter — funnel through
  `ChatViewModel.runSlashCommand`, so they cannot diverge.
- `/exportar` needs the clipboard, which only exists inside Compose (`LocalClipboardManager`).
  The VM publishes `clipboardRequest` and `ChatScreen` fulfils it and calls
  `consumeClipboardRequest()`.

### Export conversation

`ChatExport` (`domain/export/`, pure and unit-tested standalone) renders a session — or a single
turn — to Markdown. `Tool` messages are **not** dumped (hundreds of lines of JSON that say nothing
to a human reader); each tool the assistant invoked shows up as a `🔧 name` line instead. Images
and attachments are marked, never inlined: the base64 lives outside the DB and would make the
file useless. `suggestedFileName` slugifies the title with accent transliteration and restricts
the result to `[a-z0-9-_]`, because the same name has to survive NTFS.

Reachable from the top-bar `⋮` menu and the palette: copy to clipboard (all platforms) or save a
`.md` (desktop only, via `saveTextFile` — `expect`/`actual`, native dialog off the EDT; mobile
actuals return null). Copying a **single turn** is the copy icon on a user bubble (`MessageBubble`
reuses `onCopy` there, meaning "the turn", vs. "this message" on an assistant bubble).

### Command palette and keyboard shortcuts

`CommandPalette` (Ctrl/Cmd+K) filters actions and conversations in one box; ↑/↓ to move, Enter to
run, Esc to close. The list is built in `MainScaffold` from the same drawer state that renders the
sidebar, so opening it costs no DB read. Commands close the palette *before* running — several of
them open another overlay, and the other order would have `onDismiss` close what the action just
opened.

Global shortcuts live in a single `onPreviewKeyEvent` on the `MainScaffold` root: **Ctrl+K**
palette, **Ctrl+N** new chat, **Ctrl+,** settings, **Esc** closes the topmost overlay and only
stops the stream when there is nothing to close (with a dialog open, Esc means "close this"). The
preview pass runs root→focused element, so these fire even while the caret is in the composer,
which keeps Enter/Shift+Enter for itself. Ctrl+Enter already sent before this change (`AppTextField`
only treats Shift+Enter specially).

### Plan / Build mode

`AgentMode.Plan | AgentMode.Build` (default **Build**) gates whether the agent can mutate the project. The effective mode is per-session (`prefs.sessionAgentModes[sessionId]`) with `prefs.agentMode` as the global default — resolved by `ActiveWorkspaceStore.effectiveAgentMode`. In **Plan** mode the project-mutating tools (`create_file`, `edit_file`, `multi_edit`, `delete_file`, `create_directory`, `save_image`) report `isAvailable=false` via `FsToolUtil.isWriteAvailable` (= `isAvailable` && mode==Build) and are **not sent to the model** — it physically can't call them. `run_command` stays available (the agent prompt instructs Plan mode to use it read-only). `buildAgentPrompt` prepends a PLAN-MODE block telling the model to investigate and propose a plan, then ask the user to switch to Build to apply it. Toggled from the `AgentControlsBar` chip, persisted via `PreferencesRepository.updateSessionAgentMode` / `updateAgentMode`. Read tools (`read_file`, `list_directory`, `search_files`) and non-fs tools are unaffected.

### Conversation branching (resend undo)

`resendMessage` truncates the session at a user message and re-runs the model. When the truncation would discard **later user turns** (i.e. you're rewriting history mid-conversation, not just regenerating the last answer), `ChatViewModel` first calls `ChatRepository.forkSession` to save a complete copy, and assigns it to the reserved `ProjectRepository.BRANCHES_GROUP_ID` group so the drawer shows it under a collapsed "Ramas anteriores" section — same mechanism as `AUTOMATION_GROUP_ID` (a pseudo-project id, not a real `Project`, so workspace resolution still falls back to global). The user stays in the current session; only the drawer gains an entry.

- **Plain regenerate does not fork.** `regenerateLastResponse` resends the last user message, so the only thing discarded is the answer you explicitly asked to replace. Forking there would copy the whole conversation on every regenerate.
- **If the fork fails, the resend is aborted** rather than truncating anyway — the point of the feature is not losing the conversation. A failed *group assignment* is not fatal (the branch just shows as a normal session).
- `forkSession` copies in one transaction: new message ids, same `sort_order`, `pinned = false`, `checkpoint_id` **nulled** (checkpoints are keyed by source session, so a copied id would render a revert chip pointing at a turn that doesn't exist in the copy), and the transient `mediaOverlay` entries remapped to the new ids so generated images survive in the copy for the current run.
- On mobile the drawer renders a flat list and ignores project assignments entirely, so branches appear as ordinary sessions — same as automation sessions do today. Nothing is hidden.
- `ChatRepositoryLegacyImpl` doesn't implement `forkSession` — or any of `ChatRepository`: it is only the migration reader (see Persistence).

Note that **editing** a message is not destructive at all — `editMessage` only loads the text/image into the composer and sends it as a new message at the end.

### Per-turn checkpoints (agent undo)

Before a file-mutating tool runs, `SendMessageUseCase.executeCall()` snapshots the file's pre-turn state via `CheckpointStore` (`core/storage/`, `expect`/`actual`; real on desktop, no-op on mobile). Snapshots live in `~/.localchatbot/checkpoints/<sessionId>/<turnId>/` (`manifest.json` + `blobs/`), where `turnId` = the turn's user-message id. Covered tools: `create_file`, `edit_file`, `multi_edit`, `delete_file`, `create_directory`, `save_image` — mutations via `run_command`, MCP or skill scripts are NOT captured (documented in the revert dialog). Key behaviours:

- **Opaque tools (`run_command`, `mcp_*`, `sk_*`) are covered via git, not per file.** They don't declare what they touch, so before the first one of a turn `CheckpointStore.snapshotWorkspaceGit` records `git stash create` (or HEAD if the tree is clean) and revert does `git checkout <ref> -- .`. `stash create` builds the commit **without** touching the working tree or the stash stack, which is exactly what's needed. Two hard limits, both stated in the revert dialog: it needs a git workspace, and it only restores **tracked** files — anything a command created without staging survives the revert. Git runs first in `revert()` so that per-file blobs (byte-exact) win over it when both cover the same path.
- Snapshot is **idempotent per path within a turn** (the pre-turn state is what matters; benign under parallel tool execution). Recursive dir deletes snapshot the tree with caps (200 files / 20 MB, `partial` flag). A snapshot failure never breaks the tool (wrapped in `runCatching`).
- The first mutation of a turn tags the announcing assistant message with `ChatMessage.checkpointId` (via `ChatRepository.updateMessageCheckpoint`) and prunes old checkpoints (last 10 turns per session, by dir mtime).
- UI: messages with `checkpointId` render a "↩ Revertir cambios de este turno" chip (`MessageBubble` → `ChatMessageList` → `ChatScreen` → `ChatViewModel.requestRevert`); note `ChatMessageList`'s visibility filter explicitly keeps otherwise-empty announcer messages that carry a checkpoint. Confirming (`RevertTurnDialog`) restores files only — created files are deleted, edited/deleted files restored byte-for-byte, in reverse manifest order — **chat messages are kept**. Feedback goes through the existing `errorMessage` banner. `SessionsViewModel.deleteSession` also deletes the session's checkpoints.

### Available tools

Registered in `AppContainer.toolRegistry`; `sk_*` and `mcp_*` tools are built dynamically per turn.

| Tool | Requires | Notes |
|---|---|---|
| `ask_user` | — | Turn-ending question with optional chips (`endsTurn = true`). Publishes to `PendingUserPromptStore`; the answer arrives as the next user message. Backed by a deterministic nudge (see below) because local models routinely ignore the prompt rule. Note this is *not* the old "detect a question in the text" heuristic, which auto-answered itself in YOLO mode |
| `search_web` | Tavily API key | HTTP call to Tavily; results shown as source chips |
| `fetch_url` | — | Downloads a URL and returns its readable text. Closes the gap between "can search" and "can read": `search_web` returns snippets, this returns the document. No API key. HTML → text via `HtmlToText` (`core/web/`, hand-rolled — jsoup is JVM-only and this must compile for iOS native). Caps at 6k chars by default so the tool, not the generic 8k truncation, tells the model there is more page |
| `git_status` / `git_diff` / `git_log` | Desktop only | Read-only, **no confirmation**, available in Plan mode — checking the repo state shouldn't need approval. Shell out to git through `agent.runCommand` |
| `git_commit` | Desktop only | The only git tool that writes: confirmable and Build-mode only. The approval dialog shows the `--stat` of what's included — approving a commit knowing only its message isn't informed consent. Two non-obvious details, both commented in `GitTools.kt`: the message goes through a file (`git commit -F`) because `runCommand` uses a POSIX shell on macOS/Linux but **cmd on Windows** and no quoting scheme covers both; and `git add -A` runs *before* the message file is written, or it would stage that temp file into the commit |
| `generate_image` | Image Service at `:8080` | SDXL via ComfyUI; image returned out-of-band |
| `generate_text_image` | Image Service at `:8080` | Image whose content is legible text (posters, labels) |
| `render_diagram` | Image Service at `:8080` | Mermaid → PNG via mermaid-cli + headless Chromium |
| `cartoonify_image` / `animate_image` / `cartoon_video` | Image/Video Service | Transform the last produced image, or the last photo the user attached. Source resolution is a fallback chain wired in `AppContainer` (previous tool's image → `activeSessionStore.lastUserImageDataUrl`), so the tools chain naturally |
| `save_image` / `save_video` | Desktop only | Persist the last generated media to the workspace. They *peek* the out-of-band data (don't consume it, so it still renders in chat), decode base64, write via `FilesystemAgent`; confirmable. Render as a `FileActionBubble` chip |
| `read_file` / `create_file` / `edit_file` / `multi_edit` / `delete_file` / `list_directory` / `create_directory` | Desktop only | Filesystem agent tools; `edit_file` does exact-string replacement (old string must be unique unless `replace_all`) |
| `search_files` | Desktop only | Native recursive grep over the workspace (`FilesystemAgent.searchFiles`, `Files.walk`). `pattern` is regex by default with automatic literal fallback if it doesn't compile (`mode` in the payload says which ran); case-insensitive by default; optional `path`, `file_glob`, `literal`, `case_sensitive`, `max_results` (≤500). Skips binaries, files >1MB and heavy dirs (.git, build, node_modules…). Returns `path:line: text` hits relative to the workspace so chat links open the editor at that line. Read-only → no confirmation, available in Plan mode |
| `search_code_semantic` | Desktop only + embeddings model | Semantic search over the workspace via an embeddings index (see Semantic search below). Answers "where is X handled?" without knowing the name. Read-only → no confirmation, available in Plan mode. Degrades with an explicit message pointing at `search_files` when no embeddings model is available |
| `spawn_agent` | Desktop only | Runs a self-contained subtask in a **child session** with its own fresh context and returns only its final text (see Sub-agents below) |
| `run_command` | Desktop only | Shell execution tool (foreground with timeout, or background with PID); destructive patterns (`DangerousCommands.kt`) force a confirmation dialog even in YOLO |
| `manage_todos` | — | Session-scoped to-do list the model uses to plan multi-step tasks; shown in `TodoProgressPanel` |
| `use_skill` | — | Loads the full instructions for an installed skill on demand (the skills index in the system prompt only lists each skill's short description) |
| `read_tool_docs` | Desktop only | Lazily returns the tool guide (`~/.localchatbot/tools.md`). The model calls it when unsure how to use a tool or when one keeps failing; avoids bloating every request with tool docs. Default content shipped via `DEFAULT_TOOLS_MD` (`ToolDocsStore`), seeded to disk on first read; user can edit the file |
| `read_memory` / `save_memory` | Desktop only | User-preference memory (`~/.localchatbot/memory.md`). `save_memory` appends a durable preference (confirmable); `read_memory` returns the full file. A capped summary is also injected into every system prompt (see Memory below). Backed by `MemoryStore` |
| `sk_<skillId>_<scriptName>` | Desktop only | Custom per-skill shell scripts (`SkillScript`), built dynamically by `ScriptToolFactory`; each runs through the confirmation controller |
| `mcp_<serverId>_<toolName>` | MCP server (HTTP any platform, stdio desktop only) | MCP server tools, built dynamically by `McpToolProvider`; each runs through the confirmation controller |

`Tool` (`domain/tools/Tool.kt`) is the contract: `definition` (sent to the model), `execute`, `isAvailable`, `requiresConfirmation`, `endsTurn`, `activityLabel`/`activityDetail` (UI progress), and the `consume*`/`peek*` producedImage/Video hooks that keep base64 out of the model context. Tool output is truncated to `MAX_TOOL_OUTPUT_CHARS` (8k, head+tail).

### Sub-agents (`spawn_agent`)

Opens a **child chat session**, gives it one instruction, runs the full tool loop through
`SendMessageUseCase`, and returns **only the child's final assistant text** to the parent. The
point is context: a long subtask (explore a repo, read ten files to answer one question) burns
the parent's window with intermediate output that is worthless once the conclusion is drawn.
Same shape as `AutomationScheduler.runTask`: `createSession()` → `updateTitle()` →
`assignSession(id, SUBAGENTS_GROUP_ID)` → `withContext(AutoApproveConfirmations()) { sendMessage(...) }`.

- **The child starts clean** — it inherits none of the parent's history. That's the whole saving,
  so the `task` argument must be self-contained (the tool description says so to the model).
- **The child session is visible** in the drawer under `ProjectRepository.SUBAGENTS_GROUP_ID`
  ("Sub-agentes", collapsed, same treatment as automation and branch sessions). A sub-agent runs
  real tools against the workspace; hiding what it did would defeat auditing it.
- **It inherits auto-approval** (`AutoApproveConfirmations`) like scheduled tasks — nobody is
  watching the child session, and the parent is blocked waiting for it.
- **Nesting is capped at 1.** `SubAgentRun` is a `CoroutineContext` marker (same
  `AbstractCoroutineContextElement` + `Key` pattern as `AutoApproveConfirmations`) installed on the
  child's turn; `SpawnAgentTool.isAvailable()` reads it and reports false inside a child, so the
  definition is never sent to the model. `execute` re-checks it as defence in depth.
- **Circular dependency, broken in the DI.** The tool needs `SendMessageUseCase`, which needs the
  `ToolRegistry` that contains the tool. `AppContainer` passes a lazy provider (`{ sendMessage }`),
  and `sendMessage` needs an **explicit type annotation** or the inferencer recurses. `createSession`
  is declared above the tools block for the same ordering reason.
- The child shares the parent's effective workspace and `TodoTool` list (both keyed by the *active*
  session, which stays the parent's) — same as automation sessions already do.

### Semantic search (`search_code_semantic`)

Embeddings index over the workspace, so the model can ask "where is rate limiting handled?"
without knowing the name. Complements `search_files` (grep, exact, cheap); the tool description
tells the model which to reach for.

- **The index is a file, not a SQLite table** — `~/.localchatbot/semantic-index/<key>.json`,
  same hand-managed-file pattern as `hooks.json` / `memory.md` / `tools.md`. Originally because
  there was no working schema migration path (see Persistence); now that there is, it still earns
  the file: the index is bulk regenerable derived data, so a format change just discards it
  (`SEMANTIC_INDEX_VERSION`) instead of costing a migration.
- **Vectors are quantized to int8 + base64** (`quantize`/`dequantize` in `SemanticIndex.kt`). As
  JSON decimals a 768-dim embedding is ~8 KB per chunk — a 2.000-chunk workspace would be a ~15 MB
  file rewritten whole on every reindex, which is very visible on NTFS with Defender. Quantized it's
  ~2 MB. The precision loss doesn't affect *ranking*, which is all the search does.
- **Indexing is on demand only**, on the first search of a workspace, then incremental (files whose
  size+mtime are unchanged reuse their vectors). Never at startup and never on workspace change: the
  embeddings model **competes for memory with the chat model** in LM Studio, so indexing unasked
  could evict the model you're talking to. With no embeddings model available the tool returns an
  explanatory error pointing at `search_files` instead of failing blank.
- Pieces: `EmbeddingsApi` (`/v1/embeddings` on the same endpoint, `Kind.Embeddings` in the inspector),
  `SemanticIndex.kt` (pure: chunking with overlap, quantization, cosine, FNV-1a workspace key, a
  hand-rolled base64 to avoid the experimental `kotlin.io.encoding` opt-in), `SemanticIndexStore`
  (`expect`/`actual`, desktop-only: pruned file walk, atomic index write), `WorkspaceIndexer`
  (orchestration, mutex-guarded, in-memory cache) and the tool.
- The model is `prefs.embeddingsModel` (Settings → *Búsqueda semántica*), or auto-detected as the
  first model id containing "embed". The **query is embedded with the model recorded in the index**,
  not the currently configured one — vectors from two models aren't comparable. An index whose
  version, workspace or model doesn't match is discarded rather than used.
- `MIN_SCORE` (0.25) floors the results: without it a query with nothing similar in the repo still
  returns the N least-bad chunks and the model takes them for an answer.

### Scheduled tasks (`AutomationScheduler`)

`ScheduledTask` (daily at hour:minute with optional ISO `daysOfWeek`, or every `intervalMinutes`) persisted in `AppPreferences.scheduledTasks`. `AutomationScheduler` (`core/automation/`) ticks every 30 s, and each due task opens a **new chat session** with the task instructions and runs the full tool loop through `SendMessageUseCase` with `AutoApproveConfirmations`. Runs are serialized behind a mutex so the local model is never hit concurrently; `lastRunEpochMs` persists to avoid re-firing across restarts; ephemeral `status` feeds the UI (`TasksScreen` / `TasksViewModel`). Started from `AppContainer.init` **only when `PlatformCapabilities.isDesktop`** — on mobile the scheduler is constructed but inert.

### Remote access (`RemoteAccessServer`)

Desktop-only Ktor server (`ktor-server-cio` + websockets) that exposes the chats on the LAN/VPN so you can review and approve agent changes from another device. `expect fun createRemoteAccessServer(deps)`; mobile gets `NoopRemoteAccessServer`. Gated by `remoteAccessEnabled` / `remoteAccessPort` (7676) / `remoteAccessPin`, and started/stopped **reactively** from an `AppContainer.init` collector on those preferences; stopped in the desktop shutdown hook. `localIpAddresses()` (also `expect`) supplies the URLs to display. The consumer side is `RemoteViewerScreen` — an embedded `PlatformWebView` (`core/webview/`) pointed at another desktop's remote URL, remembered in `remoteViewerUrl`.

### Workspace editor (`EditorScreen`)

Built-in file browser/editor over the effective workspace, used both directly and as the target of chat file links (`path:line`). `EditorViewModel` drives navigation, open/save, in-file search with match navigation, Markdown preview, and a **diff dialog before saving** (`TextDiff.kt` against `originalContent`); `SyntaxHighlighter.kt` does the colouring. It resolves paths through `FilesystemAgent` + `ActiveWorkspaceStore`, so it honours the same workspace rules as the agent tools.

### Text/document attachments (`FilePicker`)

`rememberFilePicker` (`core/fs/FilePicker.kt`, `@Composable expect`) opens a native file picker and returns plain text prepended as a fenced code block in the user's message (`AttachedTextFile`, same model regardless of source format). Available on all three platforms — Android and iOS wire the same "attach text" button in `ChatComposer` that used to be desktop-only. Extraction is extension-based (`.pdf`, `.docx`, else raw-text decode) and implemented per platform since there's no shared jvmMain source set for Android+Desktop to share code:

- **Desktop**: `JFileChooser`/`FileDialog` (macOS) → Apache PDFBox (`org.apache.pdfbox:pdfbox`) for `.pdf`, `java.util.zip` + DOM XML parse of `word/document.xml` for `.docx`.
- **Android**: SAF `ActivityResultContracts.OpenDocument()` → `pdfbox-android` (`com.tom-roush:pdfbox-android`, AWT-free port; needs `PDFBoxResourceLoader.init()` before first use) for `.pdf`, same zip+XML approach for `.docx`.
- **iOS**: `UIDocumentPickerViewController` (`.Import` mode, copies into the sandbox — no security-scoped URL handling needed) → native `PDFKit.PDFDocument` for `.pdf`. For `.docx` there's no built-in unzip API, so `FilePicker.ios.kt` parses the ZIP structure by hand (central directory → `word/document.xml`) and inflates with Foundation's `decompressedDataUsingAlgorithm` (raw deflate); text extraction mirrors the Android/Desktop `<w:p>`/`<w:t>` criteria via namespace-tolerant regex instead of DOM.

Legacy binary `.doc` is unsupported everywhere (`onError` callback → `ChatViewModel.attachTextFileError` → `errorMessage`, same UX as stream failures). `onResult`/`onError` are plain callbacks (not suspend), matching `rememberImagePicker`'s pattern rather than the old bridge-singleton pattern used for `VoicePermissionBridge`.

### Tool docs (`read_tool_docs` / `tools.md`)

On-demand tool guide so the model can recover from misuse without bloating every request. `ToolDocsStore` (`core/storage/`, `expect`/`actual`) backs `~/.localchatbot/tools.md` (sibling of `skills/`, desktop only); if the file is missing it's seeded from `DEFAULT_TOOLS_MD` on first read. The `read_tool_docs` tool returns the whole file. The agent prompt (desktop only) tells the model to call it when unsure or when a tool keeps failing, and `edit_file`'s no-match error points there too. The default content focuses on the non-obvious behaviour models tend to miss (e.g. `edit_file`'s whitespace-tolerant fallback, line-range Mode B, the nearby-region hint). Curated by us — the user can edit the file but that's not the expected flow.

**Deterministic `edit_file` recovery:** beyond the prompt pointer, every failed `edit_file` result carries a `recovery` field injected straight into the tool output (`EDIT_FILE_RECOVERY` in `ToolDocsStore`). The model always sees the recovery steps on the next turn without having to choose to call `read_tool_docs` — `EditFileTool` wraps `FsResult.Err` into `editErrorPayload`.

### Hooks (`hooks.json`)

Shell commands run automatically **after a tool mutates the workspace** — format after `edit_file`, recompile, run tests. `HooksStore` (`core/hooks/`, `expect`/`actual`, desktop only) backs `~/.localchatbot/hooks.json`, the same hand-edited-file pattern as `tools.md` and `memory.md`: no settings screen, no settings key. Seeded with disabled examples on first read — a hook that fires unasked is the opposite of what you want from an agent.

- The hook's output is **appended to the tool result**, not sent separately, so the model sees it in the same round: if the formatter rewrote the file it just edited, or the build broke, it finds out before building further on top.
- `onlyOnFailureOutput` (default true) keeps a passing formatter from spending context on "all good".
- The file is re-read every turn, so editing it takes effect without restarting. Broken JSON yields an empty list rather than propagating — a malformed hook must not take down the turn. Output is capped at `HOOK_OUTPUT_CAP` (2k).
- **`after_turn` is not implemented yet** — see `ROADMAP.md` 2.6.

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
- **UI**: `SkillsScreen` / `SkillsViewModel` (browse, toggle, import) and `SkillCreateSheet` (author a new skill); `SkillSuggestionPopup` surfaces matching skills in the composer. Opened from `AgentScreen`/`SettingsScreen` via `onOpenSkills`.
- **`SkillsExport`** is the JSON shape for exporting/importing skill bundles.
- The repo's own `skills/` folder (e.g. `skills/best-practices/SKILL.md`) is sample/importable skill content, not app source.

### Global history search (FTS5)

"Where did I talk about X?" across the whole history. It lives in the drawer's existing search
box — the one that filtered by title — which now also searches message **content** and renders
the hits under an "En los mensajes" section. Deliberately not a third search UI: there was
already a title filter here and an in-chat search (`ChatSearchBar`).

- **`message_fts` is an external-content FTS5 table** (`content=message`): FTS5 stores only the
  index and reads the text back from `message`, instead of keeping a second copy of the whole
  history. The cost is that synchronisation is entirely on the three triggers in `Message.sq`.
  Note the delete/update triggers use the `INSERT INTO message_fts(message_fts, …) VALUES
  ('delete', …)` form with the **old** value — with external content FTS5 can no longer read
  what it must un-index. The update trigger is `AFTER UPDATE OF content` because streaming
  rewrites `content` constantly but `metrics`/`checkpoint_id` changes index nothing.
- **`2.sqm` deletes orphan messages before building the index.** `ON DELETE CASCADE` was
  declared but `PRAGMA foreign_keys` is per-connection and older builds didn't set it, so
  deleting a conversation left its messages behind — 94% of the table in a real database. They
  are unreachable from every app query (all of them start from a session), and indexing them
  would fill results with hits in conversations that can't be opened.
- **Query text is never passed raw to `MATCH`.** `toFtsMatchQuery` (`domain/search/`) quotes
  every term, because FTS5 has its own operators (`-`, `*`, `:`, `NEAR`, `OR`…) and something
  like `wi-fi` is a *syntax error*, not zero results. The last term gets a `*` so results
  narrow as you type. `searchMessages` also wraps the call in `runCatching`: a search must not
  take down the drawer.
- **Highlighting comes from SQL, not from the UI.** `snippet()` delimits matches with
  `char(2)`/`char(3)` and `parseSnippet` turns that into segments. Re-finding the query in the
  text would not work: the tokenizer is diacritic-insensitive, so "sesion" matches "sesión"
  and a literal `contains` would find nothing to mark.
- **Jumping to a result** goes through `ActiveSessionStore.selectAndScrollTo`, because
  selecting the session and being able to scroll don't happen at the same instant — messages
  load reactively afterwards. `ChatScreen` consumes the pending target once the message exists
  in the list, and its bottom auto-scroll bails out while one is pending, or the two effects
  would race.
- Search is one-shot and debounced (220 ms), not a `Flow`: re-running it on every 120 ms
  streaming write would burn work on results nobody is looking at.
- **FTS5 is why `minSdk` is 26.** It isn't guaranteed in Android's bundled SQLite before that,
  and a schema that can't be created means an app that won't start.

### Session metrics panel (no ViewModel)

`SessionMetricsScreen` (`presentation/features/metrics/`) aggregates the active session:
tokens, tok/s, estimated cost and most-used tools. Full-screen overlay from `MainScaffold`,
reachable from the chat `⋮` menu and the command palette.

- **No ViewModel and no new query.** It takes the `ChatSession` that `ChatViewModel` already
  collects and aggregates in a `remember` — `aggregateSessionMetrics` (`domain/model/SessionMetrics.kt`)
  is a pure function, same shape as `ChatExport`. Everything it needs (`metrics`, `model`,
  `toolName`) is already on the rows.
- **Tool counts come from the `tool_name` column** of `Role.Tool` messages, which existed
  already — the feature needed no schema change for that half.
- **Per-model breakdown is real**, not an approximation, since `message.model` (migration
  `1.sqm`); pre-migration messages fall back to `ChatSession.model`. The card only renders
  when the session actually used more than one model.
- **Pricing is a static table** (`domain/model/ModelPricing.kt`), matched by substring against
  the model id because providers append date/version suffixes. Nothing in `ConnectionConfig`
  knows about providers or prices, and keeping that table current is explicitly not a goal —
  local models match nothing and simply get no cost rather than an invented one. When only
  some models of a session are priced the total is flagged as partial; a silent partial would
  read as the session's full cost.

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
- `androidMain` — OkHttp engine, `ChatForegroundService` (keeps streams alive), `SharedPreferences`, SQLDelight Android driver, native SpeechRecognizer/TTS
- `iosMain` — Darwin engine, `NSUserDefaults`, SQLDelight native driver, `SFSpeechRecognizer`/`AVSpeechSynthesizer`
- `desktopMain` — CIO client engine **and** Ktor *server* (remote access), filesystem/shell agent tools, SQLDelight JDBC driver, JVM settings, JNA (Windows window chrome), `kotlinx-coroutines-swing`, TTS via the OS engine (`say` / PowerShell `System.Speech` / `spd-say`·`espeak`)

**Read-aloud (TTS):** every assistant bubble has a speaker icon (`MessageBubble`) that reads the message via `TextToSpeech` (shared instance in `AppContainer`, also used by the voice mode). `ChatViewModel.speakMessage`/`stopSpeaking` drive it and expose `speakingMessageId`; markdown is stripped before speaking. Works on all platforms (desktop TTS shells out to the OS engine). Note: full voice *conversation* mode (mic) is still mobile-only (`PlatformCapabilities.voiceSupported`).

### Opening the workspace in the OS file manager

`revealInFileManager(path)` (`core/platform/FileManagerOpener.kt`, `expect`/`actual`) opens a folder in Finder / Explorer / the Linux default; mobile actuals are no-ops since the workspace is desktop-only. The desktop actual shells out per OS (`open` / `explorer.exe` / `xdg-open`) rather than relying on `Desktop.open()`, whose directory behaviour is inconsistent across platforms — AWT stays as the fallback. It **never checks the exit code**: `explorer.exe` returns 1 even when it succeeds. It runs on a daemon thread because the call site is a Compose `onClick` (the EDT) and both the `isDirectory` probes and `CreateProcess` are slow enough on Windows to show as a frozen frame. A path pointing at a file opens its parent; a missing path is a silent no-op. Surfaced as a small `FolderOpen` button in `AgentControlsBar`, next to (not merged into) the workspace chip, which keeps its "change workspace" click; it only renders when a workspace is set, and it opens `ChatUiState.fsWorkspaceDir` — the *effective* workspace, so it follows the active session's project.

### Desktop window chrome

`desktopMain/main.kt` is the entry point and branches hard on OS:

- **macOS** — native decorations with a transparent title bar (`apple.awt.transparentTitleBar` + `fullWindowContent`, set both as system properties *and* as `rootPane` client properties), empty window title so no text shows behind the traffic lights, and `App(topInset = 28.dp)` to clear them. Dock icon is set by reflection on `com.apple.eawt.Application` (no compile-time dependency).
- **Windows** — the window is `undecorated` and the chrome is drawn in Compose: `DesktopTitleBar` (drag, minimize/maximize/close), `WindowResizeHandles` (edge/corner hit zones), and `applyWindowsRoundedCorners` (`WindowCorners.desktop.kt`, DWM via JNA). The title bar is wrapped in the same `AppTheme` as `App()` so it follows the user's theme instead of being stuck dark.
- **Linux / other** — plain decorated window, no insets.

The desktop shutdown hook (registered where `AppContainer` is created) flushes pending writes, stops the remote-access server and closes MCP clients.

### Desktop notifications (`SystemNotifier`)

Bounces the dock/taskbar icon and raises a native notification when a chat turn or scheduled task finishes, when `ask_user` asks something, and when a tool is waiting for approval (`desktopNotificationsEnabled`, on by default). `notify()` returns early if the app already has focus — the check covers **both** platforms and runs *before* `requestAttention()`, because macOS suppresses the banner itself but would still bounce the dock in your face. Notifications go through `SystemTray`/`TrayIcon` so macOS attributes them to the app itself and a click brings the window forward. **Windows is a special case**: the `TrayIcon` balloon uses the suppressed `Shell_NotifyIcon` API, so a native **WinRT toast** is emitted via PowerShell under a custom AppUserModelID; for the toast to show the app name/icon, an unpackaged app needs a Start-menu shortcut carrying that AUMID, created once at startup (`ensureWindowsRegistration` + `desktopMain/resources/win-toast-register.ps1`). Notifications are suppressed when the app already has focus (`isAppForeground`). Click-to-focus on the Windows toast is **not** implementable here — PowerShell 5.1 refuses `Register-ObjectEvent` on WinRT objects; see the long comment in `SystemNotifier.desktop.kt` before attempting it again.

## Interaction guidelines

- When you need the user to make a decision before proceeding, use the `AskUserQuestion` tool to present options. Do **not** make any other tool calls in the same response — wait for the user's answer first.
- If a question requires free-form input rather than discrete options, ask it in text and **stop** — do not continue with tool calls in that same turn.
- Code comments and user-facing strings in this codebase are in **Spanish**; match the surrounding language.

## Key constraints

- **Cleartext HTTP** is intentionally enabled (`usesCleartextTraffic="true"` in `AndroidManifest.xml` and `NSAllowsLocalNetworking` in iOS `Info.plist`) to reach local LAN servers — do not remove these.
- **Gradle must stay at 8.9** — AGP 8.7.3 is incompatible with Gradle 9.x.
- **`-Xexpect-actual-classes`** compiler flag is required (set in `build.gradle.kts`) because `expect`/`actual` classes are used across all four targets.
- **iOS framework needs `linkerOpts += "-lsqlite3"`** — SQLDelight's native driver calls sqlite3 through cinterop; Gradle's intermediate link tolerates the unresolved symbols but Xcode's final link does not.
- **Desktop distributions need `modules("java.sql")`** — the SQLite JDBC driver loads via `ServiceLoader`, so jdeps can't see it and jlink would strip the module. The failure only reproduces in the *installed* binary, never with `./gradlew run`.
- Images/videos from the generation tools are stored **out-of-band** in a `StateFlow`, never in the chat context sent to the model, and never persisted to SQLite, to avoid inflating token counts and the DB with base64.
- MSI `upgradeUuid` must stay stable, or installers stop upgrading in place and install side by side.
