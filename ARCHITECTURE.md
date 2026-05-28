# 🏗️ Arquitectura de LocalChatBot

Documentación visual de cómo está construida la app, cómo habla con tu PC servidor
y qué pasa dentro del servidor cuando le pides algo.

---

## 🌍 Vista general — ¿quién es quién?

```mermaid
flowchart LR
    subgraph PHONE["📱 Tu teléfono"]
        APP["LocalChatBot<br/>(KMP + Compose)"]
    end

    subgraph PC["💻 Tu PC (servidor local)"]
        LMS["🧠 LM Studio<br/>:1234<br/>(modelo LLM)"]
        IMG["🎨 Image Service<br/>:8080<br/>(FastAPI)"]
    end

    subgraph CLOUD["☁️ Internet (opcional)"]
        TAV["🔎 Tavily<br/>(búsqueda web)"]
    end

    APP -- "WiFi LAN<br/>chat + tools" --> LMS
    APP -- "WiFi LAN<br/>imágenes y diagramas" --> IMG
    APP -- "HTTPS<br/>solo si hay API key" --> TAV

    style APP fill:#7C4DFF,stroke:#7C4DFF,color:#fff
    style LMS fill:#2C5AFF,stroke:#2C5AFF,color:#fff
    style IMG fill:#2EBD66,stroke:#2EBD66,color:#fff
    style TAV fill:#FF8A00,stroke:#FF8A00,color:#fff
```

**¿Por qué dos servicios en el PC?**
- LM Studio sirve **chat** con un endpoint compatible con OpenAI
- El Image Service sirve **píxeles** (SDXL para imágenes artísticas, mermaid-cli
  para diagramas) — son cosas distintas y conviven en otro puerto

---

## 📲 Anatomía de la app (capas)

```mermaid
flowchart TB
    subgraph UI["🎨 Presentation (Compose)"]
        Chat["ChatScreen"]
        Sett["SettingsScreen"]
        Insp["NetworkInspector"]
        Bub["MessageBubble<br/>(burbujas, imágenes)"]
    end

    subgraph VM["🧠 ViewModels"]
        CVM["ChatViewModel"]
        SVM["SettingsViewModel"]
    end

    subgraph DOM["⚙️ Domain (lógica)"]
        SM["SendMessageUseCase"]
        TR["ToolRegistry"]
        T1["🔎 search_web"]
        T2["🎨 generate_image"]
        T3["📊 render_diagram"]
    end

    subgraph DATA["🔌 Data (red + storage)"]
        OA["OpenAiApi"]
        IG["ImageGenApi"]
        DR["DiagramRenderApi"]
        TV["TavilyApi"]
        Prefs["PreferencesRepository<br/>(settings persistidos)"]
        Sess["ChatRepository<br/>(sesiones)"]
    end

    UI --> VM
    VM --> DOM
    DOM --> DATA
    SM --> TR
    TR --> T1
    TR --> T2
    TR --> T3
    T1 --> TV
    T2 --> IG
    T3 --> DR
    SM --> OA

    style UI fill:#7C4DFF,stroke:#7C4DFF,color:#fff
    style VM fill:#2C5AFF,stroke:#2C5AFF,color:#fff
    style DOM fill:#2EBD66,stroke:#2EBD66,color:#fff
    style DATA fill:#FF8A00,stroke:#FF8A00,color:#fff
```

**Filosofía:** UI no sabe de HTTP, los UseCases no saben de Ktor, y los DTOs viven
encapsulados en `data/remote`. Si mañana cambias LM Studio por Ollama, solo tocas
`OpenAiApi`.

---

## 💬 ¿Qué pasa cuando mandas un mensaje?

Ejemplo: *"Hazme un mapa conceptual de la fotosíntesis"*

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 Usuario
    participant APP as 📱 App
    participant LM as 🧠 LM Studio
    participant DI as 📊 Image Service<br/>(diagram)

    U->>APP: Escribe mensaje y envía
    APP->>LM: POST /chat/completions<br/>(con tools disponibles)
    LM-->>APP: stream tool_calls = [render_diagram]<br/>code = "mindmap\n  root..."
    Note over APP: Muestra "Renderizando diagrama…"
    APP->>DI: POST /generate-diagram<br/>{code: "mindmap..."}
    DI-->>APP: {success: true, image_base64: "iVBOR..."}
    Note over APP: Guarda PNG en _lastImage<br/>(fuera del contexto del LLM)
    APP->>LM: POST /chat/completions<br/>+ resultado tool (sin base64)
    LM-->>APP: stream "¡Listo! Aquí tienes..."
    Note over APP: Pega el PNG al mensaje<br/>del assistant
    APP-->>U: 💬 Texto + 🖼️ diagrama
```

**Sutileza importante:** el base64 del PNG **nunca** viaja al modelo. Si lo
metiéramos en el contexto serían ~30k tokens de basura. En su lugar lo guardamos
out-of-band en un `StateFlow` y lo adjuntamos al `ChatMessage` cuando terminan
las iteraciones.

---

## 🛠️ El bucle de tool-calling

```mermaid
flowchart TB
    Start([Usuario manda mensaje]) --> Build[Construir mensajes<br/>+ system prompt]
    Build --> Stream[Stream del LLM]
    Stream --> Check{¿Pidió<br/>tool_calls?}
    Check -- "Sí" --> Exec[Ejecutar cada tool<br/>en paralelo virtual]
    Exec --> ExecW{🔎 search_web?}
    Exec --> ExecI{🎨 generate_image?}
    Exec --> ExecD{📊 render_diagram?}
    ExecW --> Push[Push resultado<br/>como role=tool]
    ExecI --> Push
    ExecD --> Push
    Push --> Limit{¿iter < 4?}
    Limit -- "Sí" --> Stream
    Limit -- "No" --> Done[Cortar para evitar loop]
    Check -- "No" --> Drain[Drenar imagen<br/>producida out-of-band]
    Drain --> Attach[Adjuntar al<br/>último ChatMessage]
    Attach --> End([UI muestra<br/>texto + imagen])
    Done --> End

    style Exec fill:#2EBD66,stroke:#2EBD66,color:#fff
    style Drain fill:#7C4DFF,stroke:#7C4DFF,color:#fff
    style End fill:#2C5AFF,stroke:#2C5AFF,color:#fff
```

---

## 🖥️ Dentro del servidor

```mermaid
flowchart TB
    subgraph PC["💻 PC servidor"]
        subgraph LMS["🧠 LM Studio (:1234)"]
            LLM["Modelo cargado<br/>(Llama, Qwen, etc.)"]
        end

        subgraph FAST["🎨 Image Service (:8080) — FastAPI"]
            IMG_EP["POST /generate-image"]
            DIAG_EP["POST /generate-diagram"]
        end

        subgraph COMFY["🖼️ ComfyUI + SDXL"]
            GPU["GPU 🔥"]
        end

        subgraph MMDC["📊 mermaid-cli"]
            PUP["Puppeteer<br/>+ Chromium headless"]
        end

        IMG_EP --> COMFY
        DIAG_EP --> MMDC
    end

    style LMS fill:#2C5AFF,stroke:#2C5AFF,color:#fff
    style FAST fill:#2EBD66,stroke:#2EBD66,color:#fff
    style COMFY fill:#FF8A00,stroke:#FF8A00,color:#fff
    style MMDC fill:#7C4DFF,stroke:#7C4DFF,color:#fff
```

- **LM Studio** ya viene con su propio endpoint compatible con OpenAI — solo
  cargas un modelo y listo.
- **Image Service** es un script FastAPI tuyo. Convive en un solo `main.py` con
  dos endpoints. Comparte el puerto 8080.
- **ComfyUI + SDXL**: pipeline determinista para generar imágenes desde texto.
  Bueno para arte, malísimo para texto legible.
- **mermaid-cli**: arranca un Chromium headless vía Puppeteer y renderiza
  código Mermaid a PNG con texto perfectamente nítido — perfecto para diagramas
  donde SDXL fallaría.

---

## 🎯 ¿Por qué dos formas de "generar imágenes"?

```mermaid
flowchart LR
    USR["👤 Usuario pide..."] --> M{¿Qué pidió?}
    M -- "un dragón épico<br/>al estilo Van Gogh" --> ART["🎨 generate_image<br/>(SDXL)"]
    M -- "un mapa conceptual<br/>de fotosíntesis" --> DGM["📊 render_diagram<br/>(Mermaid)"]
    ART --> R1["Imagen artística<br/>con texto ilegible si lo hay"]
    DGM --> R2["Diagrama nítido<br/>con texto perfecto"]

    style ART fill:#FF8A00,stroke:#FF8A00,color:#fff
    style DGM fill:#2EBD66,stroke:#2EBD66,color:#fff
```

El **system prompt** instruye al modelo a elegir la herramienta correcta. La
regla es:

> Si el resultado debería leerse con letras claras → `render_diagram`.
> Si es algo natural/artístico/fotorealista → `generate_image`.

---

## 🔁 ¿Cómo se reinstala todo si me cambio de PC?

```mermaid
flowchart TB
    Step1["1️⃣ Instalar LM Studio<br/>y cargar un modelo"] --> Step2
    Step2["2️⃣ Instalar Python 3.11+<br/>y crear venv"] --> Step3
    Step3["3️⃣ Instalar ComfyUI + SDXL<br/>(modelo base + checkpoint)"] --> Step4
    Step4["4️⃣ Instalar Node.js +<br/>@mermaid-js/mermaid-cli"] --> Step5
    Step5["5️⃣ Levantar el FastAPI<br/>(main.py con los 2 endpoints)"] --> Step6
    Step6["6️⃣ Abrir puerto 8080 en firewall<br/>y conectar el móvil a la misma WiFi"] --> Step7
    Step7["7️⃣ Configurar la IP en la app<br/>(Settings → IP + Image Service URL)"]
    Step7 --> Done(["✅ Listo para chatear,<br/>generar imágenes y diagramas"])

    style Done fill:#2EBD66,stroke:#2EBD66,color:#fff
```

Los detalles concretos están en:
- `ONBOARDING.md` — cómo instalar ComfyUI + SDXL
- `DIAGRAM_SERVICE_SETUP.md` — cómo añadir `/generate-diagram` al `main.py`

---

## 🧰 Componentes clave (mapa mental)

```mermaid
mindmap
  root((📱 LocalChatBot))
    Presentation
      ChatScreen
      MessageBubble
        ::icon(fa fa-comment)
        AttachedImage
        Image preview dialog
      SettingsScreen
      NetworkInspector
        Búsqueda + ↑↓
    Domain
      UseCases
        SendMessageUseCase
        CreateSessionUseCase
      Tools
        search_web
        generate_image
        render_diagram
      Models
        ChatMessage
        ChatSession
        AppPreferences
    Data
      OpenAiApi
      ImageGenApi
      DiagramRenderApi
      TavilyApi
      Repositorios
    Core
      Tema (Material3)
      ImageSaver expect/actual
      SystemBarsEffect
      Background executor
```

---

## 📌 Tips para los developers

- **Toca una imagen** en el chat → vista previa con botón Guardar (guarda en la
  galería del teléfono)
- **Ajustes → Inspector de red** → ves cada request/response crudo con búsqueda
  y navegación por flechas
- **Long-press en una burbuja** del assistant → Copiar / Regenerar
- **Long-press en tu mensaje** → Editar / Reenviar
- El `imageServiceUrl` por defecto es `auto: <ip-LM-Studio>:8080` — si tu Image
  Service vive en otra máquina, lo cambias manualmente
