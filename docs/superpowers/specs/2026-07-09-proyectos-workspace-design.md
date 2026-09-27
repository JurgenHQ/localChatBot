# Diseño: Proyectos con workspace por proyecto (Desktop)

**Fecha:** 2026-07-09
**Estado:** Aprobado (diseño) — pendiente plan de implementación
**Plataforma:** Desktop-only

## Problema

Hoy el workspace de las herramientas de filesystem es una **única preferencia global**
(`AppPreferences.fsWorkspaceDir`). Las sesiones son una lista plana sin noción de proyecto.
Un usuario que trabaja en varios proyectos no puede tener un workspace distinto por proyecto
ni organizar sus conversaciones por proyecto.

## Objetivo

Permitir agrupar sesiones en **proyectos opcionales**, donde cada proyecto tiene su propia
carpeta de workspace. Al estar en una sesión que pertenece a un proyecto, las herramientas fs
usan la carpeta de ese proyecto como cwd. Las sesiones sin proyecto siguen usando el
`fsWorkspaceDir` global (comportamiento actual intacto).

## Decisiones de alcance (acordadas)

- **Agrupación opcional**: se mantiene la lista plana; los proyectos son grupos encima. Una
  sesión puede no tener proyecto.
- **Proyecto = nombre + carpeta de workspace**. Nada más se scoped-ea (modelo, system prompt,
  skills, etc. siguen globales).
- **UI en secciones colapsables** dentro del drawer; sección "Sin proyecto" para las sueltas.
- **Desktop-only**: en móvil no aparece nada nuevo (las herramientas fs y el workspace ya son
  desktop-only).

## Arquitectura

### Persistencia: `ProjectRepository` dedicado (sin tocar SQLite)

El driver de SQLDelight en Desktop **no corre migraciones** (`DatabaseDriverFactory.desktop.kt`
solo llama a `Schema.create()` para BDs nuevas; no hay `Schema.migrate` ni versionado — por
diseño, se apoyan en backups diarios). Añadir una columna a la tabla `session` no se aplicaría
a las BDs existentes sin montar un arnés de migración inexistente.

Por eso el feature **no toca el esquema SQLDelight ni añade `projectId` a `ChatSession`**. Todo
vive en un repositorio nuevo, respaldado por `multiplatform-settings` (JSON), igual que otras
listas de configuración persistidas.

**Interfaz** (`domain/repository/ProjectRepository.kt`):

```kotlin
interface ProjectRepository {
    val state: Flow<ProjectState>
    suspend fun current(): ProjectState

    suspend fun createProject(name: String, workspaceDir: String): Project
    suspend fun renameProject(id: String, name: String)
    suspend fun updateWorkspace(id: String, workspaceDir: String)
    suspend fun updateCollapsed(id: String, collapsed: Boolean)
    /** Borra el proyecto; sus sesiones quedan sin proyecto (no se borran). */
    suspend fun deleteProject(id: String)

    /** Asigna la sesión a un proyecto, o la quita si projectId == null. */
    suspend fun assignSession(sessionId: String, projectId: String?)
    /** Quita la sesión de cualquier proyecto (usado al borrar sesión). */
    suspend fun detachSession(sessionId: String)
    /** Limpia toda la membresía (usado por clearAll). */
    suspend fun clearAssignments()
}
```

**Modelos** (`domain/model/Project.kt`):

```kotlin
@Serializable
data class Project(
    val id: String,
    val name: String,
    val workspaceDir: String,
    val collapsed: Boolean = false,
    val createdAtEpochMs: Long
)

@Serializable
data class ProjectState(
    val projects: List<Project> = emptyList(),
    /** sessionId -> projectId */
    val assignments: Map<String, String> = emptyMap()
)
```

**Impl** (`data/repository/ProjectRepositoryImpl.kt`): `ProjectRepositoryImpl(settings, json)`.
Persiste `ProjectState` como un blob JSON en settings (key `projects_state`). Mantiene un
`MutableStateFlow<ProjectState>` como fuente de verdad en memoria, hidratado al construir. Cada
mutación actualiza el flow y reserializa a settings. `deleteProject` elimina el proyecto y
purga sus entradas del map de `assignments`.

### Resolución del workspace efectivo: `ActiveWorkspaceStore` (Approach A)

Las herramientas fs asumen "un workspace activo a la vez" y todas lo leen de un único punto
(`FsToolUtil` → `prefs.current().fsWorkspaceDir`, más dos lecturas directas en `UseCases.kt`).
Se introduce un store que expone el **workspace efectivo de la sesión activa**:

```kotlin
class ActiveWorkspaceStore(
    activeSessionStore: ActiveSessionStore,
    projectRepository: ProjectRepository,
    preferencesRepository: PreferencesRepository,
    scope: CoroutineScope
) {
    /** Workspace efectivo: carpeta del proyecto de la sesión activa, o el global si no tiene. */
    val effectiveWorkspace: StateFlow<String?>
    suspend fun current(): String?
}
```

`effectiveWorkspace` se calcula combinando reactivamente:
`activeSessionStore.activeSessionId` + `projectRepository.state` (assignments + projects) +
`preferencesRepository.preferences.fsWorkspaceDir`.

Lógica: si la sesión activa tiene una asignación a un proyecto **existente** → `project.workspaceDir`;
en cualquier otro caso (sin proyecto, o asignación huérfana) → `fsWorkspaceDir` global.

**Puntos de consumo a cambiar** (de `prefs.fsWorkspaceDir` a `activeWorkspace.current()`):

1. `FsToolUtil.resolvePath` — usa el workspace efectivo en `resolveSafePath(workspace = ...)`.
2. `FsToolUtil.isAvailable` — `isDesktop && activeWorkspace != null` (sigue habilitando las tools
   solo si hay algún workspace resoluble, sea de proyecto o global).
3. `UseCases.kt` línea ~470 — el snapshot de checkpoint resuelve la ruta contra el workspace efectivo.
4. `UseCases.kt` línea ~894 — `buildWorkspaceContext()` construye el bloque `<workspace>` con el
   workspace efectivo.

`FsToolUtil` recibe el `ActiveWorkspaceStore` por constructor/DI (las tools fs ya reciben
dependencias así). `isWriteAvailable` no cambia (sigue añadiendo el chequeo de `AgentMode.Build`).

**Nota de correctitud:** solo hay una sesión enviando a la vez, así que un store de "workspace
activo único" es coherente con el resto del pipeline. `fsAllowOutsideWorkspace` sigue global.

### UI (drawer, Desktop-only)

`SessionsViewModel` combina `chatRepository.sessions` + `projectRepository.state` y produce una
estructura agrupada para el drawer:

- Una sección **colapsable por proyecto** (encabezado: nombre + chevron + hint de carpeta +
  menú overflow: renombrar, cambiar carpeta, borrar). Estado colapsado persistido en `Project.collapsed`.
- Una sección **"Sin proyecto"** con las sesiones cuyo id no está en `assignments` (o cuya
  asignación apunta a un proyecto inexistente — huérfanos tratados como sin proyecto).
- **"+ Nuevo proyecto"** (acción en la cabecera del drawer) → diálogo con nombre + selector de
  carpeta usando `rememberDirectoryPicker` (`DirectoryPicker.desktop`).
- **"Nueva conversación"** dentro de una sección crea la sesión y la asigna a ese proyecto; la
  de nivel superior la crea sin proyecto.
- Overflow de cada sesión: **"Mover a proyecto"** → elegir proyecto o "ninguno".
- **Borrar proyecto** → diálogo de confirmación; al confirmar, `deleteProject` desagrupa sus
  sesiones (no las borra).

En Android/iOS la UI de proyectos no se muestra (gate por `PlatformCapabilities.isDesktop`);
el drawer se comporta como hoy (lista plana).

### Consistencia de datos

- `SessionsViewModel.deleteSession` llama a `projectRepository.detachSession(id)` tras borrar la sesión.
- `ChatRepository.clearAll` (borrar todo) va acompañado de `projectRepository.clearAssignments()`
  desde el caller (p. ej. Settings). Los proyectos en sí pueden conservarse o limpiarse; se
  conservan (solo se limpia la membresía) salvo que el flujo de "borrar todo" indique lo contrario.
- Asignaciones huérfanas (sesión borrada por otra vía, o proyecto inexistente) se filtran al
  agrupar; no rompen nada.

## DI (`AppContainer`)

- `val projectRepository: ProjectRepository = ProjectRepositoryImpl(settings, json)`.
- `val activeWorkspaceStore = ActiveWorkspaceStore(activeSessionStore, projectRepository, preferencesRepository, appScope)`.
- Inyectar `activeWorkspaceStore` en las herramientas fs (vía `FsToolUtil`) y en `SendMessageUseCase`
  para las dos lecturas directas.
- Inyectar `projectRepository` en `SessionsViewModel`.

## Casos borde

- **Carpeta del proyecto inexistente en disco**: `resolveSafePath` falla de forma natural al
  operar; sin manejo especial en esta iteración (posible mejora futura: marcar el header).
- **Cambiar de sesión entre proyectos**: el `effectiveWorkspace` se recalcula; el próximo envío
  usa la carpeta nueva. Es el objetivo del feature.
- **Cambiar la carpeta de un proyecto a mitad de conversación**: idem, se recalcula reactivamente.
- **Proyecto sin sesiones**: se muestra como sección vacía con su "Nueva conversación".

## Migración de datos

Ninguna. `ProjectState` arranca vacío (todas las sesiones existentes caen en "Sin proyecto").
No hay cambios de esquema SQLite. Configs previas siguen deserializando.

## Testing

No hay tests automatizados en el repo (CLAUDE.md). Verificación manual en Desktop:

1. Crear proyecto con carpeta A → aparece sección colapsable.
2. "Nueva conversación" dentro del proyecto → sesión asignada; correr una tool fs (p. ej.
   `list_directory`) resuelve contra carpeta A.
3. Cambiar a una sesión sin proyecto → tool fs resuelve contra el `fsWorkspaceDir` global.
4. "Mover a proyecto" una sesión suelta → salta a la sección correcta y su workspace cambia.
5. Borrar el proyecto → sus sesiones vuelven a "Sin proyecto", no se borran.
6. Colapsar/expandir persiste tras reiniciar la app.

## Fuera de alcance (YAGNI)

- Scoping de modelo/system prompt/skills/MCP/modo agente por proyecto.
- Proyectos en móvil.
- Selector de proyecto como filtro superior o navegación en dos niveles (se eligió secciones
  colapsables).
- Aviso/validación de carpeta de workspace inexistente.
- Migración de la tabla `session` en SQLite.
