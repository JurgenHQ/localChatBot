# Diseño: Perfiles de conexión (máx. 3)

**Fecha:** 2026-07-15
**Estado:** Aprobado

## Objetivo

Permitir al usuario definir hasta **3 perfiles de conexión** (p. ej. "IA local" en LM Studio y "OpenAI" con suscripción pagada) y elegir cuál está activo, sin salir del chat. Un solo perfil está activo a la vez y aplica **globalmente** a toda la app (chat, tareas programadas, model picker, tools).

## Decisiones tomadas

- **Alcance global**: un perfil activo para toda la app; no hay perfil por sesión.
- **Switcher en Settings y en el chat**: gestión completa en Ajustes + selector rápido en la top bar del chat.
- **Un perfil = solo conexión**: nombre + host, puerto, HTTPS, API key y modelo (el `ConnectionConfig` actual). Los parámetros de generación, Tavily, Image Service, etc. siguen siendo globales.
- **Máximo 3 perfiles**, mínimo 1 (el último no se puede borrar).

## Modelo de datos (domain)

En `domain/model/Connection.kt`:

```kotlin
@Serializable
data class ConnectionProfile(
    val id: String,          // uuid
    val name: String,        // "IA local", "OpenAI", …
    val config: ConnectionConfig
)
```

En `AppPreferences`:

- `connectionProfiles: List<ConnectionProfile>` (1..3)
- `activeConnectionProfileId: String`
- El campo `connection: ConnectionConfig` existente **se conserva**: el repositorio lo rellena siempre con el `config` del perfil activo. Es la clave del diseño: **ningún consumidor cambia** — `SendMessageUseCase`, `ModelPickerViewModel`, el `authTokenProvider` de `AppContainer` y la derivación de `effectiveImageServiceUrl` siguen leyendo `prefs.connection` y reciben automáticamente el perfil activo (reactivo vía el `Flow<AppPreferences>`).

## Persistencia y migración (`PreferencesRepositoryImpl`)

- Nuevas keys: `connection_profiles` (JSON de la lista) y `active_connection_profile` (id).
- **Migración transparente**: si `connection_profiles` no existe al leer, la conexión legada (key actual) se envuelve como perfil único con nombre "Perfil 1" e id generado, y se marca activa. Se persiste en la primera escritura. Nadie pierde su configuración.
- `updateConnection(config)` **conserva su firma** pero escribe sobre el `config` del perfil activo → `SettingsEditorViewModel` y `OnboardingViewModel` funcionan sin cambios de lógica.
- Nuevos métodos del repositorio:
  - `setConnectionProfiles(profiles: List<ConnectionProfile>)` — capado a 3; si el perfil activo desaparece de la lista, se activa el primero restante.
  - `setActiveConnectionProfile(id: String)` — ignora ids inexistentes.
- `reset()` limpia perfiles; `exportJson()`/`importJson()` (`SettingsExport`) incluyen la lista de perfiles y el id activo.

## UI

### Settings — sección "Perfiles de conexión"

Reemplaza la sección "Conexión" actual:

- Lista de hasta 3 filas: nombre, `host:puerto`, modelo, indicador de activo (radio). Tocar la fila activa el perfil.
- Acciones por fila: **editar** y **borrar** (borrar deshabilitado si es el único perfil; borrar el activo activa el primero restante).
- Botón **"Añadir perfil"** deshabilitado al llegar a 3.
- Editar/crear abre una sheet con campo **nombre** + los campos actuales de conexión (host, puerto, HTTPS, API key, modelo con "listar modelos" y test de conexión), reutilizando los componentes/flujos existentes del editor de settings.

### Chat — switcher rápido

- El subtítulo de `AppTopBar` (hoy muestra el modelo y abre el model picker vía `onSubtitleClick`) pasa a mostrar `nombrePerfil · modelo`.
- Al tocarlo se abre un **dropdown** con los perfiles (≤3, el activo marcado) y una entrada final "Cambiar modelo…" que abre el model picker actual.
- Cambiar de perfil durante un stream **no corta** el stream en curso (su config ya está capturada al inicio del envío); el siguiente mensaje usa el perfil nuevo.

## Sin cambios

- **Onboarding**: sigue configurando la conexión (que ahora es el primer perfil).
- Tools, MCP, skills, tareas programadas: heredan el perfil activo global sin tocarse.

## Casos borde

- Borrar el perfil activo → se activa el primero restante.
- Importar settings con >3 perfiles → se truncan a 3.
- Importar settings antiguos (sin perfiles) → misma migración que la lectura legada.
- Id activo inexistente (estado corrupto) → fallback al primer perfil.

## Verificación

Sin tests automatizados en el proyecto. Verificación manual:

1. Compilar los tres targets (`compileDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`, desktop `run`).
2. En desktop: arrancar con una config previa y comprobar la migración a "Perfil 1"; crear un segundo perfil; alternar desde Settings y desde el chat; enviar mensajes con cada perfil; borrar perfiles; export/import.
