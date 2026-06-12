# Plan: Exportar / Importar configuración como JSON

Objetivo: poder exportar **todas las configuraciones** a un archivo `.json` y reimportarlas en otra máquina (Android / iOS / Desktop) sin reconfigurar nada.

Decisiones tomadas:
- **Secretos incluidos** — el JSON lleva las API keys (Tavily + `connection.apiKey`) en texto plano para que funcione directo en la otra máquina. El archivo contiene credenciales → guardarlo en lugar seguro.
- **customSkills completas** — las skills personalizadas (con sus scripts) viajan dentro del bundle.

---

## 1. Hacer el bundle serializable

- Añadir `@Serializable` a `ConnectionConfig` — `domain/model/Connection.kt`.
- Añadir `@Serializable` a `enum class ThemeMode` — `core/theme/AppTheme.kt`.
- El resto ya es serializable: `PromptTemplate`, `InstalledSkill`, `SkillDefinition`, `McpServerConfig`.

Nuevo DTO `domain/model/SettingsExport.kt`:

```kotlin
@Serializable
data class SettingsExport(
    val version: Int = 1,                 // versión de esquema → migración futura
    val connection: ConnectionConfig,
    val themeMode: ThemeMode,
    val accentSeed: Long,
    val tavilyApiKey: String,
    val defaultSystemPrompt: String,
    val promptTemplates: List<PromptTemplate>,
    val imageServiceUrl: String,
    val fsWorkspaceDir: String?,
    val fsYoloMode: Boolean,
    val fsAllowOutsideWorkspace: Boolean,
    val installedSkills: List<InstalledSkill>,
    val customSkills: List<SkillDefinition>,
    val mcpServers: List<McpServerConfig>
)
```

Se omite `onboardingDone` (es estado local de cada máquina).

---

## 2. Repositorio: export / import

Añadir a `PreferencesRepository` + `PreferencesRepositoryImpl`:

```kotlin
suspend fun exportJson(): String
suspend fun importJson(json: String)   // lanza excepción si el JSON es inválido
```

- `exportJson` → construir `SettingsExport` desde `_state.value` y `Json { prettyPrint = true }.encodeToString(...)`.
- `importJson` → decodificar con `Json { ignoreUnknownKeys = true }` y **reutilizar los setters existentes**
  (`updateConnection`, `updateThemeMode`, `updateAccent`, `updateTavilyApiKey`,
  `updateDefaultSystemPrompt`, `setPromptTemplates`, `updateImageServiceUrl`,
  `updateFsWorkspaceDir`, `updateFsYoloMode`, `updateFsAllowOutsideWorkspace`,
  `setInstalledSkills`, `setCustomSkills`, `setMcpServers`).
  Esto garantiza que `customSkills` en Desktop se persista vía `SkillFileStore` y que el `StateFlow` se actualice. **No** escribir llaves crudas.

---

## 3. I/O de archivos por plataforma (nuevo expect/actual)

No existe un selector de archivo de texto genérico (solo `ImageSaver`, `DirectoryPicker`, `ImagePicker`).
Nuevo `core/storage/SettingsFileIO.kt`, mismo patrón `@Composable rememberX` que `DirectoryPicker`:

```kotlin
@Composable
expect fun rememberSettingsExporter(onError: (String) -> Unit): (String) -> Unit
// nombre sugerido: localchatbot-settings.json

@Composable
expect fun rememberSettingsImporter(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): () -> Unit
```

- **desktop** — `JFileChooser` (save / open) → escribir / leer texto del archivo.
- **android** — SAF: `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` (mime `application/json`) vía `rememberLauncherForActivityResult`.
- **ios** — `UIDocumentPickerViewController` (export / open); para exportar también sirve el share sheet.

---

## 4. UI

- `SettingsScreen` — nueva sección **"Backup"** con dos `SettingsRow`:
  **Exportar configuración** / **Importar configuración**.
- `SettingsViewModel`:
  - `exportSettings()` → llama al repo y pasa el string al launcher exporter.
  - `importSettings(json)` → llama al repo; emite snackbar de éxito o error.
- Importar = **reemplazo total** → mostrar `AlertDialog` de confirmación antes de sobrescribir.

---

## 5. DI

`di/AppContainer.kt`: sin nuevos singletons (los launchers de archivo viven en scope de Composable).
Solo cablear los callbacks del ViewModel.

---

## Orden de implementación

1. `@Serializable` en `ConnectionConfig` + `ThemeMode`.
2. DTO `SettingsExport`.
3. `exportJson` / `importJson` en repo (interfaz + impl).
4. `SettingsFileIO` expect + 3 actuals (desktop / android / ios).
5. UI Settings + ViewModel + diálogo de confirmación.

## Notas

- El JSON exportado contiene API keys en claro → advertir al usuario en la UI (texto bajo el botón Exportar).
- `fsWorkspaceDir` es un path absoluto específico de la máquina origen → puede no existir en destino. Se importa igual; si no existe, las tools de filesystem simplemente quedan no disponibles hasta que el usuario lo reconfigure.
- No hay tests automatizados en el proyecto; verificación manual por plataforma.
