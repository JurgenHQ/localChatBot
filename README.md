# LocalChatBot

App móvil **Kotlin Multiplatform + Compose Multiplatform** (Android e iOS) para chatear con un modelo LLM local que corra en tu red, expuesto vía un endpoint compatible con **OpenAI** (LM Studio, llama.cpp server, Ollama con `/v1`, etc.).

La UI es **100% compartida** entre Android e iOS — un solo árbol de composables, dos entry points nativos.

---

## Tabla de contenidos

- [Qué hace la app](#qué-hace-la-app)
- [Stack técnico](#stack-técnico)
- [Arquitectura](#arquitectura)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Flujo de datos](#flujo-de-datos)
- [Inspector de red (debug)](#inspector-de-red-debug)
- [Prerrequisitos](#prerrequisitos)
- [Levantar el modelo local](#levantar-el-modelo-local)
- [Servidor: replicar el setup](#servidor-replicar-el-setup)
- [Ejecutar en Android](#ejecutar-en-android)
- [Ejecutar en iOS](#ejecutar-en-ios)
- [Previews](#previews)
- [Comandos útiles](#comandos-útiles)
- [Troubleshooting](#troubleshooting)

---

## Qué hace la app

- **Onboarding** al primer arranque: pide IP, puerto (con sufijo visual `/v1`) y nombre del modelo, y prueba la conexión contra `GET /v1/models` midiendo latencia.
- **Dos modos de conexión**: red local (IP + puerto) o **URL directa** (Cloudflare Tunnel, ngrok, etc.) para usar la app desde fuera de tu LAN. Toggle inline en el onboarding y en Configuración.
- **Chat con streaming**: respuestas token a token vía `POST /v1/chat/completions` (`stream=true`). Múltiples sesiones persistidas, scroll automático al último mensaje del usuario, indicador de escritura.
- **Adjuntar imágenes**: si el modelo es multimodal, se puede mandar una imagen junto con el prompt (codificada como `data:image/jpeg;base64,...`).
- **Vista previa de imágenes generadas**: tocar una imagen del chat la abre fullscreen con botón "Guardar imagen" (envía a galería en Android, Photo Library en iOS).
- **Modo voz (dos sabores)**: dictado simple (botón de micro en el composer, transcribe a texto y lo deja editar) **y** modo conversación (`VoiceConversationSheet`) — flujo manos libres que escucha, manda al modelo, lee la respuesta con TTS y vuelve a escuchar. Usa APIs nativas: Android `SpeechRecognizer` + `TextToSpeech` / iOS `SFSpeechRecognizer` + `AVSpeechSynthesizer`.
- **Web search con tools**: si configuras una API key de **Tavily**, el modelo puede invocar la tool `search_web` automáticamente cuando la pregunta necesita información actual. Las fuentes se muestran como chips bajo la respuesta.
- **Generación de imágenes con tools** (`generate_image`): si levantas el servicio multimedia local (FastAPI + ComfyUI + SDXL — ver [Servidor: replicar el setup](#servidor-replicar-el-setup)), el modelo invoca esta tool para imágenes artísticas, retratos, paisajes, etc.
- **Renderizado de diagramas con tools** (`render_diagram`): el mismo servicio expone `/generate-diagram` (wrapper de **mermaid-cli** + Chromium headless). El modelo elige esta tool — y NO `generate_image` — cuando le pides un mapa conceptual, mindmap, flowchart, secuencia, clases, ER, etc. Resultado: el texto del diagrama sale nítido y legible (los modelos de difusión no saben dibujar texto).
- **Reenviar y editar mensajes**: long-press sobre un mensaje del usuario abre un menú con "Editar" (lo carga en el composer, junto con la imagen si tenía) o "Reenviar" (vuelve a invocar al modelo con el mismo contenido). "Editar" no toca el historial: manda el texto como un mensaje nuevo al final. "Reenviar" sí descarta lo posterior, pero si con ello se perdieran turnos siguientes, antes guarda una copia completa de la conversación en la sección **Ramas anteriores** del drawer.
- **Botón de stop**: mientras el modelo procesa un prompt, el botón de enviar se reemplaza por un botón de detener que cancela el stream en curso.
- **Cambio rápido de modelo**: tocando el nombre del modelo en la barra superior del chat se abre un selector con los modelos disponibles del endpoint.
- **Barra de uso de contexto**: indicador visual encima del composer que muestra cuántos tokens lleva consumidos la conversación respecto al `context_length` real del modelo (lo lee de LM Studio cuando está disponible, 8192 como fallback). El cálculo excluye las imágenes porque van out-of-band y no consumen contexto.
- **System prompt configurable**: instrucción inicial que se antepone como mensaje `system` en cada llamada al modelo. Útil para fijar idioma, tono, persona o restricciones globales sin tener que repetirlas por sesión.
- **Biblioteca de plantillas de prompt**: guardas prompts reutilizables con título y cuerpo. Desde el composer (icono ✨) abres el sheet, eliges una y se inserta en el draft — útil para prompts largos que usas seguido (resumen de código, traducción técnica, code review, etc.).
- **Drawer lateral de sesiones**: lista de conversaciones agrupadas por fecha, búsqueda, crear nueva, borrar, fijar (pinned arriba).
- **Configuración**: edita modo de conexión, IP/puerto o URL del tunnel, modelo, URL del servicio multimedia (puede ser distinto al del LLM cuando usas tunnels), tema (System/Light/Dark), color de acento, API key de Tavily, system prompt por defecto, plantillas de prompt, e historial.
- **Inspector de red integrado** (Configuración → Desarrollador): muestra cada petición HTTP con request/response crudos, latencia, errores, y búsqueda con navegación entre coincidencias. Útil para depurar tool calls.
- **Edge-to-edge nativo**: la barra de notificaciones y la de navegación toman el color del tema activo (claro u oscuro) sin saltos visuales.
- **Background-safe**: el stream se lanza en `applicationScope` y pide al SO mantener el proceso vivo (foreground service en Android) para que la respuesta no se corte al cambiar de app.
- **Persistencia**: preferencias e historial completo guardados en `SharedPreferences` (Android) / `NSUserDefaults` (iOS) vía `multiplatform-settings`.

---

## Stack técnico

| Capa | Tecnología |
|---|---|
| Lenguaje | **Kotlin 2.1.0** |
| UI | **Compose Multiplatform 1.7.3** (Material 3) |
| Red | **Ktor 3.0.3** (engines: OkHttp en Android, Darwin en iOS) |
| Serialización | `kotlinx.serialization` JSON |
| Concurrencia | `kotlinx.coroutines` + `Flow` |
| Fechas | `kotlinx.datetime` |
| Persistencia | `multiplatform-settings` 1.2.0 |
| ViewModel | **AndroidX Lifecycle ViewModel multiplatform** 2.8.4 |
| Build | **Gradle 8.9** + **AGP 8.7.3** |
| Android SDK | min **24**, target **35** |
| iOS | iOS 13+ (Compose MP runtime) |

---

## Arquitectura

Combinamos dos enfoques:

### 1. Capas (Clean-ish)

```
Presentation (Compose + ViewModels)
       ↓ depende de
   Domain (modelos, interfaces, use cases)
       ↑ implementado por
     Data (DTOs OpenAI, Ktor, repos impl)
       ↑ usa primitivas de
     Core (theme, network, storage, state)
```

- **`core/`** — primitivas multiplataforma sin lógica de negocio: tema, factory de `HttpClient`, factory de `Settings`, `ActiveSessionStore` (estado compartido entre VMs).
- **`domain/`** — no depende de nada de UI ni red. Modelos puros (`ChatSession`, `ConnectionConfig`, `AppPreferences`), interfaces de repositorios y casos de uso (`SendMessageUseCase`, `CreateSessionUseCase`, `CheckConnectionUseCase`).
- **`data/`** — implementaciones: `OpenAiApi` (Ktor), DTOs serializables, `*RepositoryImpl` que persisten con `Settings`.
- **`presentation/`** — Compose + ViewModels.
- **`di/`** — `AppContainer`: inyección manual (sin frameworks).

### 2. Atomic Design para los componentes

```
atoms        → piezas indivisibles (AppTextField, PrimaryButton, StatusDot…)
molecules    → composiciones pequeñas (MessageBubble, SessionRow, LabeledField…)
organisms    → bloques de UI completos (ChatTopBar, AppBottomBar, ChatComposer)
features/*   → pantallas (un VM por pantalla + composable *Content* puro)
```

### 3. ViewModel por pantalla

Cada pantalla tiene su propio `ViewModel`. La comunicación entre el drawer y el chat (compartir la sesión activa) se hace mediante un `ActiveSessionStore` inyectado en ambos.

| Pantalla | ViewModel | Responsabilidad |
|---|---|---|
| `OnboardingScreen` | `OnboardingViewModel` | IP/puerto/modelo, test conexión, finish |
| `ChatScreen` | `ChatViewModel` | sesión activa, draft, send, sending/error |
| `SessionDrawer` | `SessionsViewModel` | lista, búsqueda, drawer open/close, crear/borrar |
| `SettingsScreen` | `SettingsViewModel` | prefs, estado conexión, abrir editor, clear history |
| `SettingsEditorSheet` | `SettingsEditorViewModel` | draft del campo en edición + save |

Cada pantalla expone también un `*Content(state, callbacks)` *stateless* para que los **previews** funcionen sin VM.

---

## Estructura del proyecto

```
localChatBot/
├── ARCHITECTURE.md                    ← documentación de arquitectura con diagramas Mermaid
├── README.md
├── LICENSE
├── build.gradle.kts                   ← raíz (sin código, solo plugins)
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml          ← catálogo de versiones
│
├── docs/
│   └── diagrams/                      ← diagramas ilustrativos del flujo de la app
│       ├── 01_overview.{mmd,png}
│       ├── 02_prompt_normal.{mmd,png}      (chat sin tools)
│       ├── 03_prompt_internet.{mmd,png}    (search_web)
│       ├── 04_prompt_image.{mmd,png}       (generate_image)
│       ├── 05_prompt_diagram.{mmd,png}     (render_diagram)
│       └── README.md
│
├── composeApp/
│   ├── build.gradle.kts               ← config KMP (android + iosX64/Arm64/SimulatorArm64)
│   └── src/
│       ├── commonMain/kotlin/com/localchatbot/
│       │   ├── App.kt                              ← entry composable; decide onboarding vs main
│       │   │
│       │   ├── core/                               ← infraestructura cross-cutting (no negocio)
│       │   │   ├── background/
│       │   │   │   └── BackgroundExecutor.kt           (expect: mantiene el proceso vivo durante streams)
│       │   │   ├── debug/
│       │   │   │   └── NetworkInspector.kt             (store en memoria de las últimas N llamadas HTTP)
│       │   │   ├── image/
│       │   │   │   ├── ImageDecoder.kt                 (expect: bytes → ImageBitmap)
│       │   │   │   └── ImageSaver.kt                   (expect: guarda a la galería del dispositivo)
│       │   │   ├── network/
│       │   │   │   └── HttpClientFactory.kt            (Ktor + plugins + Json compartido)
│       │   │   ├── platform/
│       │   │   │   └── UrlOpener.kt                    (expect: abre URLs externas)
│       │   │   ├── state/
│       │   │   │   ├── ActiveSessionStore.kt           (estado compartido entre VMs)
│       │   │   │   └── StreamingStateStore.kt          (tracking de streams en curso + tool activity)
│       │   │   ├── storage/
│       │   │   │   └── SettingsFactory.kt              (expect: settings persistentes)
│       │   │   ├── theme/
│       │   │   │   ├── AppTheme.kt                     (entry de Material 3)
│       │   │   │   ├── Colors.kt · Typography.kt · Dimens.kt
│       │   │   │   └── SystemBarsEffect.kt             (expect: barras de sistema con color del tema)
│       │   │   └── voice/
│       │   │       ├── SpeechRecognizer.kt             (expect: dictado nativo)
│       │   │       ├── TextToSpeech.kt                 (expect: lectura del assistant)
│       │   │       ├── MicPermission.kt                (expect: permiso de micrófono)
│       │   │       ├── MarkdownToSpeech.kt             (limpia markdown antes de TTS)
│       │   │       └── VoiceConversationController.kt  (controla el bucle voz↔chat)
│       │   │
│       │   ├── domain/                             ← reglas de negocio, sin dependencias externas
│       │   │   ├── model/        (Chat, Connection, AppPreferences, PromptTemplate)
│       │   │   ├── repository/   (ChatRepository, PreferencesRepository, StreamEvent — interfaces)
│       │   │   ├── tools/        (Tool + ToolRegistry, WebSearchTool, ImageGenerationTool,
│       │   │   │                  DiagramRenderTool)
│       │   │   └── usecase/      (UseCases.kt: CreateSession, SendMessage, CheckConnection,
│       │   │                      ListModels — todos juntos, incluye el bucle de tool-calling)
│       │   │
│       │   ├── data/                               ← implementaciones, talkers a APIs externas
│       │   │   ├── remote/
│       │   │   │   ├── OpenAiApi.kt + OpenAiDtos.kt    (chat/completions con tool calling + streaming)
│       │   │   │   ├── LmStudioApi.kt + LmStudioDtos.kt (extras de LM Studio: context length real)
│       │   │   │   ├── TavilyApi.kt                    (web search)
│       │   │   │   ├── ImageGenApi.kt + ImageGenDtos.kt (FastAPI /generate-image)
│       │   │   │   ├── DiagramRenderApi.kt + DiagramRenderDtos.kt (FastAPI /generate-diagram)
│       │   │   │   └── ToolDtos.kt                     (DTOs comunes de OpenAI tool calling)
│       │   │   └── repository/   (ChatRepositoryImpl, ModelRepositoryImpl, PreferencesRepositoryImpl)
│       │   │
│       │   ├── di/
│       │   │   └── AppContainer.kt                 ← composition root manual (todo se cablea aquí)
│       │   │
│       │   └── presentation/
│       │       ├── components/                     ← Atomic Design
│       │       │   ├── atoms/        (AppIcon, AppTextField, Buttons, StatusDot, SectionLabel,
│       │       │   │                  TypingIndicator + AtomsPreviews)
│       │       │   ├── molecules/    (MessageBubble, SessionRow, LabeledField, SuggestionChip,
│       │       │   │                  ConnectionStatusBadge, SettingsRow, SectionCard,
│       │       │   │                  ContextUsageBar, ModelPickerList, SourceChip
│       │       │   │                  + MoleculesPreviews)
│       │       │   └── organisms/    (AppTopBar, AppBottomBar, ChatComposer + OrganismsPreviews)
│       │       ├── features/
│       │       │   ├── onboarding/   (OnboardingViewModel + OnboardingScreen — toggle Red local /
│       │       │   │                  URL directa, prueba de conexión, picker de modelo)
│       │       │   ├── chat/         (ChatViewModel + ChatScreen + ChatEmptyState — burbujas,
│       │       │   │                  composer, vista previa de imágenes, dismiss del teclado)
│       │       │   ├── sessions/     (SessionsViewModel + SessionDrawer — lista, búsqueda, pin)
│       │       │   ├── settings/     (SettingsViewModel + SettingsEditorViewModel + screens,
│       │       │   │                  toggle de modo de conexión, editores en bottom sheet)
│       │       │   ├── templates/    (PromptTemplatesSheet — biblioteca de prompts reutilizables)
│       │       │   ├── voice/        (VoiceConversationSheet — UI del modo conversación por voz)
│       │       │   └── debug/        (NetworkInspectorScreen — único feature sin ViewModel, ver
│       │       │                      sección "Inspector de red")
│       │       ├── navigation/       (MainScaffold — tabs Chat / Configuración)
│       │       └── preview/          (PreviewSurface, PreviewSamples)
│       │
│       ├── androidMain/
│       │   ├── AndroidManifest.xml                 (permisos: INTERNET, RECORD_AUDIO, etc.)
│       │   ├── res/values/themes.xml               (edge-to-edge, transparent bars)
│       │   └── kotlin/com/localchatbot/
│       │       ├── MainActivity.kt                 (entry)
│       │       ├── LocalChatBotApp.kt              (Application; inicializa AppContextHolder)
│       │       ├── AppContextHolder.kt             (acceso al Context desde commonMain vía actuals)
│       │       └── core/
│       │           ├── background/
│       │           │   ├── BackgroundExecutor.android.kt   (lanza ChatForegroundService)
│       │           │   └── ChatForegroundService.kt        (notificación persistente durante streams)
│       │           ├── image/
│       │           │   ├── ImageDecoder.android.kt         (BitmapFactory → ImageBitmap)
│       │           │   └── ImageSaver.android.kt           (MediaStore → Pictures/)
│       │           ├── platform/UrlOpener.android.kt       (Intent ACTION_VIEW)
│       │           ├── storage/SettingsFactory.android.kt  (SharedPreferences)
│       │           ├── theme/SystemBarsEffect.android.kt   (enableEdgeToEdge dinámico)
│       │           └── voice/
│       │               ├── SpeechRecognizer.android.kt     (android.speech.SpeechRecognizer)
│       │               ├── TextToSpeech.android.kt         (android.speech.tts.TextToSpeech)
│       │               └── MicPermission.android.kt        (ActivityCompat.requestPermissions)
│       │
│       └── iosMain/kotlin/com/localchatbot/
│           ├── MainViewController.kt               (expuesto a Swift como
│           │                                        MainViewControllerKt.MainViewController())
│           └── core/
│               ├── background/BackgroundExecutor.ios.kt    (no-op; iOS gestiona background diferente)
│               ├── image/
│               │   ├── ImageDecoder.ios.kt                 (UIImage → Skia ImageBitmap)
│               │   └── ImageSaver.ios.kt                   (UIImageWriteToSavedPhotosAlbum)
│               ├── platform/UrlOpener.ios.kt               (UIApplication.openURL)
│               ├── storage/SettingsFactory.ios.kt          (NSUserDefaults)
│               ├── theme/SystemBarsEffect.ios.kt           (no-op; iOS controla las barras)
│               └── voice/
│                   ├── SpeechRecognizer.ios.kt             (SFSpeechRecognizer + AVAudioEngine)
│                   ├── TextToSpeech.ios.kt                 (AVSpeechSynthesizer)
│                   └── MicPermission.ios.kt                (AVAudioSession.requestRecordPermission)
│
└── iosApp/                                          ← (a crear con Xcode, ver sección iOS)
```

---

## Flujo de datos

### Enviar un mensaje

```
ChatScreen
   ↓ chatViewModel.send()
ChatViewModel
   ↓ SendMessageUseCase(sessionId, text)
        ↓ chatRepository.appendMessage(user msg)
        ↓ modelRepository.sendChat(baseUrl, model, history)
              ↓ OpenAiApi.chatCompletion → POST /v1/chat/completions
              ↑ ChatCompletionResponse
        ↓ chatRepository.appendMessage(assistant msg)
   ↑ Result<ChatMessage>
ChatViewModel actualiza StateFlow → recomposición de ChatScreen
```

### Estado compartido entre VMs

- **`ChatRepository`** expone `sessions: Flow<List<ChatSession>>` (single source of truth, persistido en JSON).
- **`PreferencesRepository`** expone `preferences: Flow<AppPreferences>`.
- **`ActiveSessionStore`** expone `activeSessionId: Flow<String?>`. Lo escribe el drawer al seleccionar y el chat al crear; lo lee `ChatViewModel` para componer su `ChatUiState`.

Resultado: si el usuario cambia el modelo en Configuración, el top bar del chat se actualiza solo. Si borra una sesión activa, el chat vuelve a empty state automáticamente.

---

## Inspector de red (debug)

Una pantalla pensada para desarrolladores que muestra el JSON crudo de **cada petición HTTP** que la app hace al modelo o a los servicios multimedia. Útil sobre todo para depurar tool calls cuando el modelo se comporta raro.

**Cómo abrirlo**: Configuración → Desarrollador → Inspector de red.

**Qué ves en la lista** (más reciente arriba):

| Campo | Ejemplo |
|---|---|
| Método y URL | `POST http://192.168.1.42:1234/v1/chat/completions` |
| Tipo | `Chat` · `ImageGen` · `DiagramRender` |
| Estado | `200 · 1.4s`, `400 · 50ms`, `error · timeout`, etc. |
| Hora | `14:32:08` |

**Qué ves al tocar una transacción**:

- Request body completo (mensaje del usuario, definiciones de tools, parámetros).
- Response body completo (incluyendo el streaming de SSE concatenado para `/chat/completions`).
- Búsqueda con resaltado de coincidencias y navegación con `↑/↓` para saltar entre matches.
- Las imágenes base64 se truncan a un placeholder `...<truncado N chars>...` para que la UI no explote con bloques de 2MB de texto.

### Arquitectura (es la única feature sin ViewModel)

```
APIs (OpenAiApi, DiagramRenderApi, etc.)
        │ inspector.record(transaction)
        ▼
┌─────────────────────────────────────────────────┐
│  NetworkInspector  (clase plain, singleton)     │
│  ───────────────                                │
│  StateFlow<List<NetworkTransaction>>            │
│  buffer circular de 50 items, en memoria        │
└─────────────────────────────────────────────────┘
        │ inspector.entries
        ▼
NetworkInspectorScreen  (Composable, sin VM)
   estado de UI local con `remember { mutableStateOf(...) }`
   (selección, búsqueda, match actual)
```

Decisión consciente: el "estado de dominio" es trivial (lista append-only), el estado de UI es efímero y local, y no hay side effects ni use cases — un ViewModel sería boilerplate vacío. Además, el inspector es un **singleton** vive en `AppContainer` y lo usan las APIs (capa de datos) para escribir; si fuera un ViewModel viviría atado al ciclo de vida de una pantalla y la capa de datos no podría empujarle eventos. Si en algún momento crece (export a archivo, replay de requests, filtros persistentes), se envuelve en `NetworkInspectorViewModel` sin más.

---

## Prerrequisitos

### Software

- **macOS** (para iOS) — Android se puede en cualquier OS.
- **JDK 17+** (`brew install --cask temurin@17`).
- **Android Studio** Koala (2024.1) o superior, con SDK 35.
- **Xcode** 15+ (solo iOS).
- **Gradle**: no hace falta instalarlo, el wrapper se incluye.
- **Node 18+** (solo en la máquina del servidor multimedia): necesario si vas a habilitar el endpoint `/generate-diagram`. `npx` se encarga de bajar `mermaid-cli` al vuelo, no hay que instalar nada globalmente.

### Variables que ya están en el proyecto

- `local.properties` con `sdk.dir` apuntando al Android SDK (se genera al abrir el proyecto).
- Manifest Android con `usesCleartextTraffic="true"` para poder pegarle a HTTP en LAN.

---

## Levantar el modelo local

La app espera un endpoint compatible con OpenAI en `http://<ip>:<puerto>/v1`. Opciones:

### LM Studio (lo más fácil)

1. Descarga LM Studio → carga un modelo `.gguf` → tab **Local Server** → Start.
2. Asegúrate que **Server Host** sea `0.0.0.0` (no `localhost`) si vas a conectarte desde otro dispositivo de la red.
3. Endpoint resultante: `http://<ip-del-mac>:1234/v1`.

### llama.cpp server

```bash
./llama-server -m model.gguf --host 0.0.0.0 --port 1234
```

### Ollama

```bash
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

Endpoint OpenAI-compatible: `http://<ip>:11434/v1` (versiones recientes).

> Encuentra la IP LAN de tu Mac con `ipconfig getifaddr en0`.

---

## Servidor: replicar el setup

Esta sección documenta cómo montar **desde cero** la máquina servidor que aloja los modelos (LLM + generación de imágenes), tal como está montada en mi máquina de desarrollo. La app móvil habla con esta máquina por la LAN.

### Hardware de referencia

- **GPU**: NVIDIA RTX 3060 (12 GB VRAM). Con menos VRAM puedes usar modelos cuantizados más pequeños; con más, modelos grandes sin cuantizar.
- **SO**: Windows 11 (los pasos son equivalentes en Linux/macOS, sólo cambian rutas y comandos de activación de venv).
- **Almacenamiento**: ~30 GB libres (LLMs cuantizados ocupan 4-8 GB, SDXL Base ~6.5 GB).
- **CUDA**: 12.4 con drivers NVIDIA actualizados.

### Visión general de los servicios

| Servicio | Puerto | Qué hace |
|---|---|---|
| **LM Studio** | `1234` | Sirve LLMs locales con API OpenAI-compatible (`/v1/chat/completions`, `/v1/models`). |
| **ComfyUI** | `8188` | Backend de Stable Diffusion. UI nodal + API HTTP para generar imágenes. |
| **Servicio multimedia** | `8080` | Wrapper FastAPI con **dos endpoints**: `POST /generate-image` (imágenes SDXL vía ComfyUI) y `POST /generate-diagram` (Mermaid → PNG vía `mermaid-cli` + Chromium headless). La app consume ambos como tools. |

```
   App móvil
      │
      ├──► http://<ip>:1234/v1   ── LM Studio  (LLM)
      │
      └──► http://<ip>:8080      ── Servicio multimedia (FastAPI)
                                       │
                                       ├──► http://localhost:8188 (ComfyUI → SDXL)   [/generate-image]
                                       │
                                       └──► npx mmdc + Chromium headless             [/generate-diagram]
```

> En modo URL directa (Cloudflare Tunnel, ngrok, etc.) sustituye `http://<ip>:1234` por la URL del tunnel del LLM, y `http://<ip>:8080` por la del servicio multimedia (pueden ser tunnels distintos — la app permite configurar ambas URLs por separado).

### 1. LM Studio (LLM)

1. Descarga LM Studio desde <https://lmstudio.ai/>.
2. Pestaña **Discover** → busca y descarga un modelo, p.ej. `qwen2.5-7b-instruct` (Q4_K_M) o `llama-3.1-8b-instruct`.
3. Pestaña **Local Server** → carga el modelo descargado.
4. En la configuración del servidor:
   - **Server Host**: `0.0.0.0` (importante: con `127.0.0.1` solo escucha desde la propia máquina, el móvil no podrá conectarse).
   - **Port**: `1234` (default).
   - Activa **Enable Cross-Origin Resource Sharing (CORS)**.
5. Click **Start Server**.
6. Verifica desde el móvil (mismo WiFi) abriendo `http://<ip-del-servidor>:1234/v1/models` en el navegador. Debe devolver un JSON con la lista de modelos.

### 2. ComfyUI (backend de imágenes)

1. Clona el repo:
   ```bash
   git clone https://github.com/comfyanonymous/ComfyUI.git
   cd ComfyUI
   ```
2. Crea un entorno virtual con **Python 3.11** y actívalo:
   ```bash
   # Windows
   py -3.11 -m venv venv
   venv\Scripts\activate

   # Linux/macOS
   python3.11 -m venv venv
   source venv/bin/activate
   ```
3. Instala PyTorch 2.6 con CUDA 12.4:
   ```bash
   pip install torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cu124
   ```
4. Instala el resto de dependencias:
   ```bash
   pip install -r requirements.txt
   ```
5. **Descarga el modelo SDXL Base 1.0** desde Hugging Face:
   <https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/blob/main/sd_xl_base_1.0.safetensors>
   Colócalo en `ComfyUI/models/checkpoints/sd_xl_base_1.0.safetensors` (~6.46 GB).
6. Lanza ComfyUI:
   ```bash
   python main.py --listen 0.0.0.0 --port 8188
   ```
   `--listen 0.0.0.0` lo hace accesible desde la red local (necesario si el Image Service corre en otra máquina; si va en la misma, puedes dejar el default).

> En Windows puedes crear un `run_comfyui.bat`:
> ```bat
> @echo off
> cd /d D:\proyectos\ComfyUI
> call venv\Scripts\activate
> python main.py --listen 0.0.0.0 --port 8188
> ```

### 3. Image Service (FastAPI wrapper)

El servicio expone una API HTTP simple que internamente le habla a ComfyUI mediante un workflow JSON. La app móvil **no** habla directo con ComfyUI: habla con este wrapper para que el contrato sea estable y sencillo.

#### Endpoint

```
POST http://<host>:8080/generate-image
Content-Type: application/json

{
  "prompt": "a red dragon, fantasy art, detailed, 4k",
  "negative_prompt": "blurry, ugly, watermark",   // opcional
  "width": 1024,    // opcional, default 1024
  "height": 1024,   // opcional, default 1024
  "steps": 20,      // opcional, default 20
  "cfg": 7.0,       // opcional, default 7.0
  "seed": -1        // opcional, -1 = aleatorio
}
```

**Respuesta exitosa**:
```json
{
  "success": true,
  "image_base64": "<base64 PNG>",
  "image_path": "D:\\proyectos\\ComfyUI\\output\\api_gen_00001_.png",
  "filename": "api_gen_00001_.png",
  "seed": 4275532229
}
```

**Respuesta con error**:
```json
{ "success": false, "error": "descripción del error" }
```

Health check: `GET /health` → `{"status":"ok"}`.

#### Levantar el servicio

1. En una carpeta paralela a ComfyUI:
   ```bash
   mkdir image-service && cd image-service
   python -m venv venv
   ```
2. Activa el venv e instala dependencias:
   ```bash
   pip install fastapi uvicorn[standard] requests pillow
   ```
3. Crea `main.py` con un wrapper mínimo (ver más abajo).
4. Lanza con:
   ```bash
   uvicorn main:app --host 0.0.0.0 --port 8080
   ```

#### Esqueleto del wrapper (`main.py`)

```python
import base64
import json
import time
import uuid
from pathlib import Path

import requests
from fastapi import FastAPI
from pydantic import BaseModel

COMFY_URL = "http://localhost:8188"
COMFY_OUTPUT = Path("D:/proyectos/ComfyUI/output")  # ajusta a tu ruta

app = FastAPI()


class GenerateRequest(BaseModel):
    prompt: str
    negative_prompt: str | None = None
    width: int = 1024
    height: int = 1024
    steps: int = 20
    cfg: float = 7.0
    seed: int = -1


def build_workflow(req: GenerateRequest) -> dict:
    """Workflow ComfyUI: CheckpointLoader → CLIPTextEncode (pos/neg) → KSampler → VAEDecode → SaveImage."""
    return {
        "3": {"class_type": "KSampler", "inputs": {
            "seed": req.seed if req.seed != -1 else int(time.time()),
            "steps": req.steps, "cfg": req.cfg, "sampler_name": "euler",
            "scheduler": "normal", "denoise": 1.0,
            "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]
        }},
        "4": {"class_type": "CheckpointLoaderSimple",
              "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
        "5": {"class_type": "EmptyLatentImage",
              "inputs": {"width": req.width, "height": req.height, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode",
              "inputs": {"text": req.prompt, "clip": ["4", 1]}},
        "7": {"class_type": "CLIPTextEncode",
              "inputs": {"text": req.negative_prompt or "", "clip": ["4", 1]}},
        "8": {"class_type": "VAEDecode",
              "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage",
              "inputs": {"filename_prefix": "api_gen", "images": ["8", 0]}}
    }


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/generate-image")
def generate(req: GenerateRequest):
    client_id = str(uuid.uuid4())
    workflow = build_workflow(req)

    # 1. Encolar el prompt en ComfyUI
    r = requests.post(f"{COMFY_URL}/prompt",
                      json={"prompt": workflow, "client_id": client_id})
    if not r.ok:
        return {"success": False, "error": f"ComfyUI rechazó el prompt: {r.text}"}
    prompt_id = r.json()["prompt_id"]

    # 2. Polling hasta que termine
    for _ in range(300):  # 5 min max
        h = requests.get(f"{COMFY_URL}/history/{prompt_id}").json()
        if prompt_id in h:
            outputs = h[prompt_id]["outputs"]
            for node_out in outputs.values():
                for img in node_out.get("images", []):
                    filename = img["filename"]
                    path = COMFY_OUTPUT / filename
                    b64 = base64.b64encode(path.read_bytes()).decode()
                    return {
                        "success": True,
                        "image_base64": b64,
                        "image_path": str(path),
                        "filename": filename,
                        "seed": workflow["3"]["inputs"]["seed"],
                    }
            break
        time.sleep(1)
    return {"success": False, "error": "timeout esperando a ComfyUI"}
```

> Este wrapper es deliberadamente simple. Si quieres soporte de LoRAs, ControlNet, upscalers, refiner SDXL, etc., generas el workflow con la UI de ComfyUI, exportas el JSON desde el menú "Save (API Format)" y lo embebes en `build_workflow`.

### 4. Endpoint de diagramas (`/generate-diagram`)

Para mapas conceptuales, mindmaps, flowcharts, secuencia, clases, ER, gantt, etc. usamos **Mermaid** rendererizado con `mermaid-cli` (headless Chromium). Los modelos de difusión generan imágenes hermosas pero ilegibles cuando hay texto; este endpoint produce PNGs con texto perfecto porque no "genera" pixel a pixel — parsea código Mermaid de forma determinista.

#### Prerrequisito

```bash
# Node 18+ (lo más fácil: instalar desde https://nodejs.org)
# mermaid-cli se descarga al vuelo con npx, no hace falta instalarlo globalmente
```

Verifica:
```bash
npx -y -p @mermaid-js/mermaid-cli mmdc --version
```

#### Contrato del endpoint

```
POST http://<host>:8080/generate-diagram
Content-Type: application/json

{
  "code": "mindmap\n  root((Fotosíntesis))\n    Luz solar\n    CO₂\n    H₂O\n    Glucosa",
  "theme": "default",          // opcional: default | dark | forest | neutral
  "background": "transparent", // opcional
  "width": 2400,               // opcional, ancho del canvas en px
  "scale": 3                   // opcional, factor de escala (3× = nítido en mobile HD)
}
```

**Respuesta** (misma forma que `/generate-image` para que la app trate ambos igual):
```json
{ "success": true, "image_base64": "<base64 PNG>", "filename": "diagram_xxx.png" }
```

#### Esqueleto en `main.py`

Añadir al mismo FastAPI que sirve `/generate-image`:

```python
import subprocess, tempfile, base64
from pathlib import Path

class DiagramRequest(BaseModel):
    code: str
    theme: str | None = None
    background: str | None = None
    width: int | None = 2400
    scale: int | None = 3


@app.post("/generate-diagram")
def generate_diagram(req: DiagramRequest):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        in_file = tmp / "in.mmd"
        out_file = tmp / "out.png"
        in_file.write_text(req.code, encoding="utf-8")

        cmd = ["npx", "-y", "-p", "@mermaid-js/mermaid-cli", "mmdc",
               "-i", str(in_file), "-o", str(out_file),
               "-w", str(req.width or 2400),
               "-s", str(req.scale or 3)]
        if req.theme:      cmd += ["-t", req.theme]
        if req.background: cmd += ["-b", req.background]

        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if proc.returncode != 0 or not out_file.exists():
            return {"success": False, "error": proc.stderr.strip() or "mmdc falló"}

        b64 = base64.b64encode(out_file.read_bytes()).decode()
        return {"success": True, "image_base64": b64, "filename": out_file.name}
```

> **Por qué `width=2400 scale=3`**: la app manda estos valores por defecto. Con `scale=3` el PNG sale ~7200px efectivos — no se pixela ni con zoom fuerte en pantallas HD. Si tu hardware es justo, baja a `scale=2`.

### 5. Script para levantar todo junto (Windows)

`run_all.bat`:
```bat
@echo off
start "ComfyUI" cmd /k "cd /d D:\proyectos\ComfyUI && call venv\Scripts\activate && python main.py --listen 0.0.0.0 --port 8188"
timeout /t 5
start "ImageService" cmd /k "cd /d D:\proyectos\image-service && call venv\Scripts\activate && uvicorn main:app --host 0.0.0.0 --port 8080"
echo Recuerda arrancar LM Studio a mano (UI grafica).
```

Equivalente Linux/macOS con `tmux`:
```bash
#!/usr/bin/env bash
tmux new-session -d -s ai 'cd ~/proyectos/ComfyUI && source venv/bin/activate && python main.py --listen 0.0.0.0 --port 8188'
tmux split-window -t ai 'cd ~/proyectos/image-service && source venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8080'
tmux attach -t ai
```

### 6. Verificar que todo funciona desde el móvil

Antes de configurar la app, prueba desde un navegador o `curl` en la misma red:

```bash
# LLM disponible
curl http://<ip-servidor>:1234/v1/models

# Image Service vivo
curl http://<ip-servidor>:8080/health

# Generar una imagen de prueba (tarda 10-20 s)
curl -X POST http://<ip-servidor>:8080/generate-image \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a small red apple on a wooden table"}'
```

Si los tres responden, ya puedes configurar la app:
- **IP / Puerto / Modelo** → de LM Studio (`<ip>`, `1234`, nombre del modelo cargado).
- **Ajustes → Generación de imágenes → URL del servicio** → déjalo en blanco para que use automáticamente `http://<ip>:8080`, o pon el endpoint explícito si el Image Service vive en otra máquina.

### Firewall y red

- **Windows**: al iniciar ComfyUI / uvicorn la primera vez, Windows Defender preguntará si permites el tráfico entrante. Acepta **"Redes privadas"**.
- **Router**: necesario que ambos dispositivos (servidor y móvil) estén en la **misma WiFi** o haya routing entre ellos. El AP guest aislado **no funciona**.
- **IP fija recomendada**: configura DHCP reservation en tu router para la IP del servidor; así no cambia y no tienes que reconfigurar la app.

### Troubleshooting servidor

| Síntoma | Causa probable | Solución |
|---|---|---|
| App ve LM Studio en `0` pero `Sin verificar` | LM Studio escucha en `127.0.0.1` | Cambia "Server Host" a `0.0.0.0` y reinicia |
| `/generate-image` tarda muchísimo o falla | ComfyUI no encontró `sd_xl_base_1.0.safetensors` | Verifica que el archivo esté en `ComfyUI/models/checkpoints/` |
| `OutOfMemoryError` en ComfyUI | VRAM insuficiente | Usa `--lowvram` o `--medvram` al lanzar ComfyUI |
| Imagen sale negra | VAE en fp16 con SDXL falla en algunas GPUs | Lanza ComfyUI con `--force-fp32` |
| El móvil no encuentra el servidor | Firewall o WiFi distinta | Prueba `curl` desde otro dispositivo de la red; revisa Windows Defender |

---

## Ejecutar en Android

### Con Android Studio (recomendado)

1. `File → Open` y selecciona la carpeta `localChatBot`.
2. Espera el Gradle Sync. Aceptar la instalación del SDK 35 si lo pide.
3. Selecciona un dispositivo (físico vía USB con depuración activa, o un AVD API 24+).
4. Run config **composeApp** → ▶.

### Por terminal

Instala en el dispositivo conectado:

```bash
./gradlew :composeApp:installDebug
adb shell am start -n com.localchatbot/.MainActivity
```

Build sin instalar:

```bash
./gradlew :composeApp:assembleDebug
# APK: composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Release no firmado:

```bash
./gradlew :composeApp:assembleRelease
```

---

## Ejecutar en iOS

El módulo Kotlin ya expone `MainViewController()` desde `composeApp/src/iosMain/kotlin/com/localchatbot/MainViewController.kt`. Falta el proyecto Xcode que lo embeba.

### 1. Crear el proyecto Xcode (una vez)

```bash
mkdir -p iosApp
```

En Xcode: **File → New → Project → iOS → App**
- Product Name: `iosApp`
- Interface: **SwiftUI**
- Language: **Swift**
- Guárdalo dentro de `localChatBot/iosApp/`.

### 2. Embeber el framework de Compose

En el target `iosApp` de Xcode:

**Build Phases → + → New Run Script Phase** (colócala **antes** de "Compile Sources"):

```bash
cd "$SRCROOT/.."
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

**Build Settings**:
- `Framework Search Paths` → `$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
- `Other Linker Flags` → `$(inherited) -framework ComposeApp`

### 3. Renderizar Compose desde SwiftUI

Reemplaza `ContentView.swift`:

```swift
import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}
```

### 4. Permitir HTTP local

Edita `iosApp/Info.plist`:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
<key>NSLocalNetworkUsageDescription</key>
<string>LocalChatBot necesita acceder al modelo en tu red local.</string>
```

### 5. Ejecutar

En Xcode selecciona un simulador (iPhone 15 p. ej.) → ▶.

> **Simulador**: comparte la red del Mac. Si el modelo escucha en `localhost:1234`, usa la IP LAN del Mac (`ipconfig getifaddr en0`), no `localhost`.
> **iPhone físico**: teléfono y máquina del modelo en la **misma WiFi**, apuntar a la IP LAN.

### Compilar el framework por terminal (sin Xcode IDE)

Útil para CI o para verificar que iOS compila sin abrir Xcode:

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

---

## Previews

Compose Multiplatform soporta `@Preview` de `org.jetbrains.compose.ui.tooling.preview` en `commonMain`. Los renderiza **Android Studio** (Xcode no tiene renderer todavía).

Hay previews para:

- **Átomos** → `AtomsPreviews.kt`
- **Moléculas** → `MoleculesPreviews.kt`
- **Organismos** → `OrganismsPreviews.kt`
- **Pantallas** → al final de cada `*Screen.kt`, con variantes claro/oscuro/sending/error/empty.

Para verlos: abre cualquier `*.kt` con previews en Android Studio y usa la vista **Split / Design** arriba a la derecha.

---

## Comandos útiles

| Acción | Comando |
|---|---|
| Sync / resolver dependencias | `./gradlew help` |
| Compilar Android | `./gradlew :composeApp:compileDebugKotlinAndroid` |
| Compilar iOS sim arm64 | `./gradlew :composeApp:compileKotlinIosSimulatorArm64` |
| Build APK debug | `./gradlew :composeApp:assembleDebug` |
| Instalar en dispositivo | `./gradlew :composeApp:installDebug` |
| Limpiar | `./gradlew clean` |
| Linkar framework iOS | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` |
| Lint Android | `./gradlew :composeApp:lintDebug` |

---

## Troubleshooting

### "Unresolved reference 'Column'" o similar en `SectionCard`

El slot composable debe recibir `ColumnScope.()` no `Column.()`. Ya está corregido.

### Android Studio fuerza Gradle 9.0 y AGP no es compatible

Asegúrate de que `gradle/wrapper/gradle-wrapper.properties` apunta a **8.9** (no 9.x). Regenera el wrapper:

```bash
gradle wrapper --gradle-version 8.9
```

(Si no tienes Gradle global instalado, usa el que ya esté cacheado en `~/.gradle/wrapper/dists/`).

### "Cleartext HTTP traffic not permitted" en Android

Ya está habilitado en `AndroidManifest.xml` con `usesCleartextTraffic="true"`. Si lo cambias, recuerda revertirlo o el modelo en HTTP dejará de funcionar.

### iOS bloquea la conexión HTTP

Confirma que añadiste `NSAllowsLocalNetworking=true` en `Info.plist`. En iOS 14+ también puede pedirte permiso de "red local" al primer request — acepta.

### El simulador iOS no encuentra el modelo en `localhost`

El simulador NO comparte `localhost` con `host.docker.internal`-style. Usa la IP LAN del Mac (`ipconfig getifaddr en0`), por ejemplo `192.168.1.42:1234`.

### Cambios en `commonMain` no aparecen en iOS al correr desde Xcode

El run script `embedAndSignAppleFrameworkForXcode` debe estar **antes** de "Compile Sources". Si lo metiste después, Xcode usa el framework viejo.

### "Deprecated Gradle features … incompatible with Gradle 9.0"

Es un warning informativo de AGP 8.7. No bloquea el build. Se irá al subir a AGP 8.9+.

---

## Licencia

Distribuido bajo licencia **MIT**. Eres libre de usar, copiar, modificar y distribuir el proyecto, incluso comercialmente, siempre que conserves el aviso de copyright. Texto completo en [`LICENSE`](LICENSE).
