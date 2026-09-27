# Plan de implementación: Proyectos con workspace por proyecto (Desktop)

Basado en `2026-07-09-proyectos-workspace-design.md`. Ordenado de dominio → datos → core →
tools/usecases → DI → UI, para poder compilar tras cada bloque grande.

## Fase 1 — Dominio (modelos + interfaz)

**1.1** `domain/model/Project.kt` (nuevo)
- `@Serializable data class Project(id, name, workspaceDir, collapsed = false, createdAtEpochMs)`.
- `@Serializable data class ProjectState(projects: List<Project> = emptyList(), assignments: Map<String,String> = emptyMap())`.

**1.2** `domain/repository/ProjectRepository.kt` (nuevo) — interfaz del spec:
`state: Flow<ProjectState>`, `current()`, `createProject`, `renameProject`, `updateWorkspace`,
`updateCollapsed`, `deleteProject`, `assignSession`, `detachSession`, `clearAssignments`.

**Checkpoint:** compila commonMain (`./gradlew :composeApp:compileKotlinDesktop` o compileDebugKotlinAndroid).

## Fase 2 — Datos (persistencia)

**2.1** `data/repository/ProjectRepositoryImpl.kt` (nuevo) — `ProjectRepositoryImpl(settings, json)`:
- `MutableStateFlow<ProjectState>` hidratado en init desde settings key `projects_state`
  (JSON; si falta o falla el parse → `ProjectState()`).
- Cada mutación: actualiza el flow y reserializa a settings (helper `persist()`).
- `createProject`: genera id (`newId()`), añade a `projects`, `createdAtEpochMs = Clock.System.now()`.
- `deleteProject`: elimina el proyecto y purga del map `assignments` toda entrada con ese projectId.
- `assignSession(sessionId, null)` == `detachSession`.
- Mirar `PreferencesRepositoryImpl` para el patrón de settings + Json (misma instancia `json` del DI).

**Checkpoint:** compila.

## Fase 3 — Core (workspace efectivo)

**3.1** `core/state/ActiveWorkspaceStore.kt` (nuevo, commonMain):
- Constructor: `ActiveSessionStore`, `ProjectRepository`, `PreferencesRepository`, `CoroutineScope`.
- `effectiveWorkspace: StateFlow<String?>` vía `combine(activeSessionId, projectState, preferences)`:
  - Si `activeSessionId` está en `assignments` y su `projectId` existe en `projects` →
    `project.workspaceDir`.
  - Si no → `preferences.fsWorkspaceDir` (global).
  - `stateIn(scope, SharingStarted.Eagerly, initial = null)`.
- `suspend fun current(): String? = effectiveWorkspace.value` (o `.first()` si prefieres esperar hidratación).

**Checkpoint:** compila.

## Fase 4 — Tools + UseCases (consumir el workspace efectivo)

**4.1** `domain/tools/FsToolUtil.kt`:
- Añadir dependencia al `ActiveWorkspaceStore` (pasar como parámetro a `isAvailable` /
  `resolvePath`, o inyectar). Decisión: pasar `activeWorkspace: ActiveWorkspaceStore` como
  parámetro a los métodos, o cambiar su firma para recibir el workspace ya resuelto. Preferible:
  método `suspend fun currentWorkspace(activeWorkspace): String?` y usarlo en:
  - `isAvailable` → `isDesktop && activeWorkspace.current() != null`.
  - `resolvePath` → `resolveSafePath(workspace = activeWorkspace.current(), ...)`.
- `isWriteAvailable` sigue añadiendo el chequeo `AgentMode.Build` sobre `isAvailable`.

**4.2** Actualizar cada tool fs que llama a `FsToolUtil` (`ReadFileTool`, `CreateFileTool`,
`EditFileTool`, `MultiEditTool`, `DeleteFileTool`, `ListDirectoryTool`, `CreateDirectoryTool`,
`SearchFilesTool`, `SaveImageTool`, `SaveVideoTool`, `RunCommandTool` si aplica) para pasar el
`ActiveWorkspaceStore` (recibido por constructor vía DI).

**4.3** `domain/usecase/UseCases.kt`:
- Inyectar `ActiveWorkspaceStore` en `SendMessageUseCase`.
- Línea ~470 (snapshot de checkpoint): `resolveSafePath(activeWorkspace.current(), mutatedPath, current.fsAllowOutsideWorkspace)`.
- Línea ~894 (`buildWorkspaceContext`): `val ws = activeWorkspace.current() ?: return null`.

**Checkpoint:** compila desktop. Verificar que las tools fs se construyen con la nueva dependencia.

## Fase 5 — DI (`di/AppContainer.kt`)

- `val projectRepository: ProjectRepository = ProjectRepositoryImpl(settings, json)`.
- `val activeWorkspaceStore = ActiveWorkspaceStore(activeSessionStore, projectRepository, preferencesRepository, <scope>)`
  — usar el mismo scope de app que ya exista (revisar cómo se crea `appScope`/similar; si no hay,
  crear un `CoroutineScope(SupervisorJob() + Dispatchers.Default)` como los demás stores).
- Pasar `activeWorkspaceStore` a las tools fs y a `SendMessageUseCase`.
- Pasar `projectRepository` a `SessionsViewModel`.

**Checkpoint:** compila desktop + arranca (`./gradlew :composeApp:run`) sin regresión: las tools
fs deben seguir funcionando con el workspace global cuando no hay proyectos.

## Fase 6 — UI (drawer, Desktop-only)

**6.1** `SessionsViewModel`:
- Inyectar `ProjectRepository`.
- Combinar `sessions` + `projectRepository.state` → estado agrupado:
  `sinProyecto: List<ChatSession>` + `grupos: List<Pair<Project, List<ChatSession>>>`
  (huérfanos → sin proyecto). Solo poblar grupos si `PlatformCapabilities.isDesktop`.
- Callbacks: `createProject(name, dir)`, `renameProject`, `updateProjectWorkspace`,
  `deleteProject`, `toggleCollapsed`, `moveSessionToProject(sessionId, projectId?)`,
  `newSessionInProject(projectId)`.
- `deleteSession`: tras borrar, `projectRepository.detachSession(id)`.

**6.2** `SessionDrawer.kt`:
- Render de secciones colapsables (encabezado con nombre, chevron, hint de carpeta, overflow).
- Sección "Sin proyecto".
- Botón "+ Nuevo proyecto" en cabecera → diálogo (nombre + `rememberDirectoryPicker`).
- "Nueva conversación" por sección.
- Overflow por sesión con "Mover a proyecto".
- Diálogo de confirmación de borrado de proyecto.
- Gate visual: si no es desktop, comportamiento actual (lista plana).

**6.3** Diálogos nuevos (o sheets, según patrón existente): crear/editar proyecto, mover sesión,
confirmar borrado. Reusar componentes atoms/molecules existentes (`AppTextField`, `PrimaryButton`,
diálogos ya presentes en el proyecto).

**Checkpoint:** `./gradlew :composeApp:run` — recorrer los 6 pasos de verificación manual del spec.

## Fase 7 — Consistencia y remates

- Donde se invoca `clearAll` (Settings): añadir `projectRepository.clearAssignments()`.
- Confirmar que `flushPendingWrites`/cierre de app no requiere flush del ProjectRepository
  (settings escribe síncrono; si no, añadir flush).
- Revisar `SettingsExport`/import: decidir si `ProjectState` entra en el backup JSON. Por defecto
  **no** en esta iteración (fuera de alcance salvo que se pida) — dejar nota.

## Orden de commits sugerido

1. Dominio (modelos + interfaz).
2. `ProjectRepositoryImpl` + wiring settings.
3. `ActiveWorkspaceStore` + cambios en FsToolUtil/UseCases + DI.
4. UI del drawer (VM + composables + diálogos).
5. Consistencia (detach en delete, clearAssignments).

Sin referencias a Claude/Anthropic en los mensajes de commit.

## Riesgos / a vigilar

- **Firma de `FsToolUtil`**: es el punto más invasivo (lo consumen ~10 tools). Verificar que
  todas se actualizan y que ninguna quede leyendo `prefs.fsWorkspaceDir` directo.
- **Scope del `ActiveWorkspaceStore`**: `effectiveWorkspace` debe estar poblado antes del primer
  envío; usar `SharingStarted.Eagerly`.
- **Huérfanos**: siempre tratar asignación a proyecto/sesión inexistente como "sin proyecto".
