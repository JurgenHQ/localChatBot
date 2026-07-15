# Plan de implementación: Perfiles de conexión (máx. 3)

Basado en `2026-07-15-perfiles-conexion-design.md`. Ordenado de dominio → datos → UI de
Settings → UI del chat, para poder compilar tras cada bloque grande.

Decisión clave que simplifica todo el plan: `AppPreferences.connection` deja de ser un campo
almacenado y pasa a ser una **propiedad calculada** a partir de `connectionProfiles` +
`activeConnectionProfileId`. Solo hay 3 sitios que construyen `AppPreferences(...)` con el
constructor completo (`AppPreferences.Default`, `PreferencesRepositoryImpl.load()`, y los dos
`SamplePrefs*` de preview en `SettingsScreen.kt`) — todos se tocan en la Fase 1/3. El resto del
código (`ModelPickerViewModel`, `ChatViewModel`, `OnboardingViewModel`, `SettingsEditorViewModel`)
solo **lee** `prefs.connection` o llama a `updateConnection(config)`, y sigue funcionando sin
cambios porque ambos siguen existiendo con la misma forma.

## Fase 1 — Dominio (modelos + interfaz)

**1.1** `domain/model/Connection.kt` — nuevo tipo:
```kotlin
@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val config: ConnectionConfig = ConnectionConfig()
)
```

**1.2** `domain/model/AppPreferences.kt`:
- Quitar `connection: ConnectionConfig` del constructor primario.
- Añadir `connectionProfiles: List<ConnectionProfile>` y `activeConnectionProfileId: String`.
- Añadir propiedad calculada:
  ```kotlin
  val connection: ConnectionConfig
      get() = connectionProfiles.firstOrNull { it.id == activeConnectionProfileId }?.config
          ?: ConnectionConfig()
  ```
- `AppPreferences.Default`: incluir **un perfil por defecto** para que el invariante "siempre
  ≥1 perfil" se cumpla también en frío / tras un futuro `reset()`:
  ```kotlin
  connectionProfiles = listOf(ConnectionProfile(id = "default", name = "Perfil 1", config = ConnectionConfig())),
  activeConnectionProfileId = "default"
  ```

**1.3** `domain/repository/PreferencesRepository.kt` — nuevos métodos:
- `suspend fun setConnectionProfiles(profiles: List<ConnectionProfile>)` (capa a 3, reasigna
  el activo si el actual desaparece de la lista).
- `suspend fun setActiveConnectionProfile(id: String)` (ignora ids inexistentes).
- `updateConnection(config: ConnectionConfig)` **se mantiene igual** en la interfaz (ahora
  escribe sobre el perfil activo).

**1.4** `domain/model/SettingsExport.kt`:
- Sustituir `connection: ConnectionConfig` por `connectionProfiles: List<ConnectionProfile> = emptyList()`
  y `activeConnectionProfileId: String = ""`.
- Mantener `connection: ConnectionConfig? = null` como campo **deprecado, nullable, con
  default `null`**, solo para poder leer backups antiguos (`ignoreUnknownKeys` ya está activo,
  pero como el campo cambia de nombre/tipo necesitamos el fallback explícito en `importJson`,
  ver 2.7).

**Checkpoint:** compila commonMain (`./gradlew :composeApp:compileKotlinDesktop`).

## Fase 2 — Datos (`PreferencesRepositoryImpl`)

**2.1** Nuevo serializer: `connectionProfilesSerializer = ListSerializer(ConnectionProfile.serializer())`.

**2.2** Nuevas keys: `KEY_CONNECTION_PROFILES = "connection_profiles"`,
`KEY_ACTIVE_CONNECTION_PROFILE = "active_connection_profile"`.

**2.3** Renombrar el `loadConnection(default)` actual a `loadLegacyConnection(default)` (se
conserva tal cual, incluida su migración de `KEY_CONN_MODE`/`KEY_DIRECT_URL` — sigue siendo la
fuente de la migración one-shot).

**2.4** Nueva función `loadConnectionProfiles(): Pair<List<ConnectionProfile>, String>`:
- Si `KEY_CONNECTION_PROFILES` existe y decodifica a una lista no vacía → usarla; el activo es
  `settings.getStringOrNull(KEY_ACTIVE_CONNECTION_PROFILE)` si apunta a un id de la lista, si no
  el primero.
- Si no existe (primer arranque tras esta versión, con o sin conexión legada previa) → construir
  `loadLegacyConnection(default)`, envolverlo en `ConnectionProfile(id = newId(), name = "Perfil 1", config = legacy)`,
  **persistirlo inmediatamente** (`settings.putString(KEY_CONNECTION_PROFILES, ...)` +
  `settings.putString(KEY_ACTIVE_CONNECTION_PROFILE, ...)`) para que la migración corra una sola
  vez, y devolverlo.
- No se borran las keys legadas de conexión (`KEY_IP`, `KEY_PORT`, etc.) — quedan huérfanas pero
  inocuas, igual que ya se hace con otras migraciones en este archivo.

**2.5** `load()`: reemplazar `connection = loadConnection(default.connection)` por el resultado
de 2.4 asignado a `connectionProfiles`/`activeConnectionProfileId`. Quitar el parámetro
`connection` (ya no existe en el constructor).

**2.6** `updateConnection(config)` — reescribir para actuar sobre el perfil activo:
```kotlin
override suspend fun updateConnection(config: ConnectionConfig) {
    val current = _state.value
    val updated = current.connectionProfiles.map {
        if (it.id == current.activeConnectionProfileId) it.copy(config = config) else it
    }
    persistConnectionProfiles(updated)
    _state.value = current.copy(connectionProfiles = updated)
}

private fun persistConnectionProfiles(profiles: List<ConnectionProfile>) {
    settings.putString(KEY_CONNECTION_PROFILES, templatesJson.encodeToString(connectionProfilesSerializer, profiles))
}
```

**2.7** Nuevos métodos:
```kotlin
override suspend fun setConnectionProfiles(profiles: List<ConnectionProfile>) {
    val capped = profiles.take(3)
    persistConnectionProfiles(capped)
    val activeStillValid = capped.any { it.id == _state.value.activeConnectionProfileId }
    val nextActive = if (activeStillValid) _state.value.activeConnectionProfileId else capped.firstOrNull()?.id.orEmpty()
    if (!activeStillValid) settings.putString(KEY_ACTIVE_CONNECTION_PROFILE, nextActive)
    _state.value = _state.value.copy(connectionProfiles = capped, activeConnectionProfileId = nextActive)
}

override suspend fun setActiveConnectionProfile(id: String) {
    if (_state.value.connectionProfiles.none { it.id == id }) return
    settings.putString(KEY_ACTIVE_CONNECTION_PROFILE, id)
    _state.value = _state.value.copy(activeConnectionProfileId = id)
}
```

**2.8** `exportJson()` / `importJson()`:
- Export: `connectionProfiles = _state.value.connectionProfiles, activeConnectionProfileId = _state.value.activeConnectionProfileId`
  (no escribir el campo deprecado `connection`).
- Import:
  ```kotlin
  val profiles = export.connectionProfiles.ifEmpty {
      val legacy = export.connection ?: ConnectionConfig()
      listOf(ConnectionProfile(id = newId(), name = "Perfil 1", config = legacy))
  }.take(3)
  setConnectionProfiles(profiles)
  setActiveConnectionProfile(export.activeConnectionProfileId.ifBlank { profiles.first().id })
  ```
  (sustituye la línea `updateConnection(export.connection)` actual).

**2.9** `reset()`: añadir `KEY_CONNECTION_PROFILES`, `KEY_ACTIVE_CONNECTION_PROFILE` a la lista de
keys borradas.

**Checkpoint:** compila. Arrancar desktop una vez con datos de conexión existentes (del checkout
actual) y verificar en logs/inspector que tras el primer `load()` se creó "Perfil 1" con esos
mismos datos (migración transparente).

## Fase 3 — UI de Settings (reutilizando el editor de campo único existente)

Se reutiliza al máximo `SettingsEditor` / `SettingsEditorViewModel` / `SettingsEditorSheet`: las
filas Host/Puerto/HTTPS/Modelo/API key siguen editando exactamente lo mismo que hoy (ahora es
el perfil activo). Solo se añade una fila más para el nombre del perfil y un bloque nuevo de
lista/alta/baja de perfiles.

**3.1** `SettingsViewModel.kt` (mismo archivo, `sealed interface SettingsEditor`):
- Añadir `data object ProfileName : SettingsEditor`.

**3.2** `SettingsEditorViewModel.kt`:
- En el `when` de `textDraft` (init): añadir
  `SettingsEditor.ProfileName -> prefs.connectionProfiles.firstOrNull { it.id == prefs.activeConnectionProfileId }?.name ?: ""`.
- En `save()`: añadir
  ```kotlin
  SettingsEditor.ProfileName -> {
      val cur = preferences.current()
      val updated = cur.connectionProfiles.map {
          if (it.id == cur.activeConnectionProfileId) it.copy(name = s.textDraft.trim()) else it
      }
      preferences.setConnectionProfiles(updated)
  }
  ```
  (`canSaveText` ya cubre este caso vía la rama `else -> textDraft.isNotBlank()`, no requiere cambio).

**3.3** `SettingsViewModel.kt` — nuevos métodos:
```kotlin
fun activateProfile(id: String) = viewModelScope.launch { preferences.setActiveConnectionProfile(id) }

fun addProfile() = viewModelScope.launch {
    val cur = preferences.current()
    if (cur.connectionProfiles.size >= 3) return@launch
    val profile = ConnectionProfile(
        id = com.localchatbot.core.util.newId(),
        name = "Perfil ${cur.connectionProfiles.size + 1}"
    )
    preferences.setConnectionProfiles(cur.connectionProfiles + profile)
    preferences.setActiveConnectionProfile(profile.id)
}

fun deleteProfile(id: String) = viewModelScope.launch {
    val cur = preferences.current()
    if (cur.connectionProfiles.size <= 1) {
        _message.value = "Debe quedar al menos un perfil de conexión"
        return@launch
    }
    preferences.setConnectionProfiles(cur.connectionProfiles.filter { it.id != id })
}
```
(`setConnectionProfiles` ya reasigna el activo si el borrado era el activo — Fase 2.7).

**3.4** `SettingsScreen.kt`:
- `SettingsContent`: nuevos parámetros `onActivateProfile: (String) -> Unit`,
  `onAddProfile: () -> Unit`, `onDeleteProfile: (String) -> Unit`.
- Antes de la actual `SectionLabel("Servidor")`, insertar:
  ```kotlin
  SectionLabel("Perfiles de conexión")
  SectionCard {
      preferences.connectionProfiles.forEachIndexed { idx, profile ->
          SettingsRow(
              title = profile.name,
              onClick = { onActivateProfile(profile.id) },
              trailing = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      if (profile.id == preferences.activeConnectionProfileId) {
                          Icon(Icons.Default.Check, contentDescription = "Activo", tint = MaterialTheme.colorScheme.primary)
                      }
                      if (preferences.connectionProfiles.size > 1) {
                          IconButton(onClick = { onDeleteProfile(profile.id) }) {
                              Icon(Icons.Default.Delete, contentDescription = "Borrar perfil")
                          }
                      }
                  }
              }
          )
          if (idx < preferences.connectionProfiles.lastIndex) Divider()
      }
      if (preferences.connectionProfiles.size < 3) {
          Divider()
          SettingsRow(title = "+ Añadir perfil", onClick = onAddProfile, trailing = {})
      }
  }
  ```
- En la SectionCard "Servidor" existente, añadir una fila "Nombre" al principio (usa
  `SettingsEditor.ProfileName`), antes de Host/Puerto/HTTPS/Modelo/API key/Estado (sin tocar
  esas filas).
- `SettingsScreen` (composable con el ViewModel): pasar `onActivateProfile = viewModel::activateProfile`,
  `onAddProfile = viewModel::addProfile`, `onDeleteProfile = viewModel::deleteProfile`.
- `SamplePrefs` / `SamplePrefsUrl` (previews, línea ~567): sustituir `connection = ConnectionConfig(...)`
  por `connectionProfiles = listOf(ConnectionProfile(id = "p1", name = "Perfil 1", config = ConnectionConfig(...)))`,
  `activeConnectionProfileId = "p1"`.

**Checkpoint:** `./gradlew :composeApp:run` — crear un segundo y tercer perfil desde Settings,
activar cada uno, editar host/modelo de cada uno por separado, borrar uno no activo y luego el
activo (verificar que se reasigna), confirmar que al llegar a 3 la fila "+ Añadir perfil"
desaparece.

## Fase 4 — Switcher rápido en el chat

**4.1** `presentation/components/organisms/AppTopBar.kt`:
- Importar `androidx.compose.material3.DropdownMenu` y `androidx.compose.foundation.layout.ColumnScope`.
- Envolver el `Text(subtitle, ...)` en un `Box` y añadir parámetros:
  ```kotlin
  subtitleMenuExpanded: Boolean = false,
  onSubtitleMenuDismiss: () -> Unit = {},
  subtitleMenuContent: (@Composable ColumnScope.() -> Unit)? = null
  ```
- Dentro de ese `Box`, tras el `Text`, renderizar:
  ```kotlin
  DropdownMenu(expanded = subtitleMenuExpanded, onDismissRequest = onSubtitleMenuDismiss) {
      subtitleMenuContent?.invoke(this)
  }
  ```

**4.2** `ChatViewModel.kt`:
- `ChatUiState`: añadir `connectionProfiles: List<ConnectionProfile> = emptyList()` y
  `activeConnectionProfileId: String = ""`.
- En el `combine` que construye `ChatUiState` (línea ~245 en adelante), poblar ambos desde
  `prefs.connectionProfiles` / `prefs.activeConnectionProfileId`.
- Nuevo método: `fun switchConnectionProfile(id: String) = viewModelScope.launch { preferences.setActiveConnectionProfile(id) }`.

**4.3** `ChatScreen.kt`:
- El composable con `viewModel` (el que llama a `ChatScreen(...)` desde fuera, ~línea 55-130):
  añadir `onSwitchProfile: (String) -> Unit = {}` como parámetro y pasarlo hacia abajo; en
  `MainScaffold.kt` cablearlo a `chatViewModel::switchConnectionProfile`.
- En el composable interno con la lista de mensajes (~línea 250 en adelante):
  - `var profileMenuOpen by remember { mutableStateOf(false) }`.
  - `val activeProfileName = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }?.name`.
  - Cambiar `subtitle` para anteponer el perfil:
    ```kotlin
    subtitle = buildString {
        activeProfileName?.let { append(it); append(" · ") }
        append(
            when {
                state.modelName.isBlank() -> "Sin modelo"
                state.modelLoaded == false -> "Sin modelo cargado"
                else -> state.modelName
            }
        )
    }
    ```
  - `onSubtitleClick = { profileMenuOpen = true }` (antes era `onChangeModel` directo).
  - Pasar a `ChatTopBar`:
    ```kotlin
    subtitleMenuExpanded = profileMenuOpen,
    onSubtitleMenuDismiss = { profileMenuOpen = false },
    subtitleMenuContent = {
        state.connectionProfiles.forEach { profile ->
            DropdownMenuItem(
                text = { Text(profile.name) },
                leadingIcon = if (profile.id == state.activeConnectionProfileId) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else null,
                onClick = { profileMenuOpen = false; onSwitchProfile(profile.id) }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Cambiar modelo…") },
            onClick = { profileMenuOpen = false; onChangeModel() }
        )
    }
    ```

**Checkpoint:** `./gradlew :composeApp:run` — con 2+ perfiles creados en Fase 3, abrir el chat,
tocar el subtítulo, cambiar de perfil desde el dropdown, comprobar que el subtítulo se actualiza
y que el siguiente mensaje usa el nuevo host/modelo. Confirmar que "Cambiar modelo…" sigue
abriendo el `ModelPickerSheet` de siempre.

## Fase 5 — Consistencia y remates

- Revisar que no queda ninguna otra construcción `AppPreferences(connection = ...)` sin migrar
  (Fase 1/3 ya cubren los 3 sitios encontrados: `Default`, `load()`, `SamplePrefs*`).
- `OnboardingViewModel`, `ModelPickerViewModel`, `SessionsViewModel`: no requieren cambios (solo
  leen `prefs.connection` / llaman a `updateConnection`, ambos siguen existiendo).
- No hay callers actuales de `PreferencesRepository.reset()` en la UI — no se requiere cambio
  adicional más allá de mantener sus keys actualizadas (Fase 2.9).

## Orden de commits sugerido

1. Dominio (`ConnectionProfile`, `AppPreferences` con `connection` calculada, interfaz de
   repositorio, `SettingsExport`).
2. `PreferencesRepositoryImpl` (migración, `updateConnection` reescrito, altas/bajas/activación,
   export/import).
3. UI de Settings (nueva fila "Nombre", lista de perfiles, alta/baja/activación, previews).
4. Switcher del chat (`AppTopBar`, `ChatViewModel`, `ChatScreen`, wiring en `MainScaffold`).

Sin referencias a Claude/Anthropic en los mensajes de commit.

## Riesgos / a vigilar

- **`connection` como propiedad calculada**: es el cambio más invasivo pero de bajo riesgo real
  — solo 3 sitios construían `AppPreferences` con el campo, y el resto del código ya solo lo
  *lee* o llama a `updateConnection`. Verificar en la compilación que no queda ningún
  `.copy(connection = ...)` suelto (ya no compilaría, lo cual es la red de seguridad).
- **Import de backups antiguos**: el campo `connection` deprecado en `SettingsExport` debe seguir
  ahí (nullable, default `null`) solo para que `importJson` pueda envolverlo si el backup es de
  antes de esta versión.
- **Invariante "≥1 perfil"**: garantizado por `AppPreferences.Default` (ya trae uno) y por
  `deleteProfile`/`setConnectionProfiles`, que nunca dejan la lista vacía ni el activo apuntando
  a un id inexistente.
