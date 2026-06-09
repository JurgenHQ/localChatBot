# Plan: LocalChatBot en Android Auto y CarPlay

## Estrategia

Ni Google ni Apple permiten apps de "chat genérico" en el coche. La vía viable es presentar
LocalChatBot como **app de mensajería con interacción 100% por voz**: el asistente es un
"contacto", sus respuestas llegan como mensajes que el sistema lee por TTS, y el usuario
responde dictando por voz.

- **Android Auto**: mensajería vía notificaciones `MessagingStyle` + `CarAppExtender`. No
  requiere UI propia ni permiso previo de Google. Se prueba con el Desktop Head Unit (DHU).
- **CarPlay**: mensajería vía SiriKit (`INSendMessageIntent`). Requiere el *CarPlay
  communication entitlement* de Apple (solicitud previa, puede tardar semanas o ser denegada).
- **Conectividad**: resuelta — el teléfono lleva **ZeroTier** activo y alcanza el servidor
  LLM casero por datos móviles. No se necesita servidor expuesto ni modelo on-device.
  La app corre en el teléfono; el coche es solo pantalla/micro/altavoces.

**Orden recomendado**: empezar por Android Auto (sin permiso previo, testeable sin coche) y
abordar CarPlay cuando Apple conceda el entitlement.

## Riesgos principales

1. El entitlement de CarPlay puede ser denegado → solicitarlo desde el día 1.
2. La revisión de Google Play para Auto es estricta con apps "asistente" disfrazadas de
   mensajería → la app debe comportarse genuinamente como mensajería (reply + mark-as-read
   funcionales, sin UI distractora).
3. Latencia voz → LLM → TTS por LTE + VPN: si supera ~5–8 s la experiencia es mala →
   perfil coche con respuestas cortas y `max_tokens` bajo.
4. Sin cobertura móvil (túneles, garajes) no hay LLM → los errores deben comunicarse por voz.

---

## Fase 0 — Preparación (en paralelo, sin código)

- [ ] Solicitar el **CarPlay communication entitlement** en developer.apple.com
      (CarPlay entitlement request). Hacerlo YA: el tiempo de respuesta es el cuello de
      botella de la Fase 3.
- [ ] Verificar que **ZeroTier** queda activo en background en Android e iOS
      (sin opción "desconectar al bloquear pantalla"). ZeroTier One está disponible en
      Google Play y App Store.
- [ ] Decidir el nombre del "contacto" del asistente (p. ej. "Asistente") y el system prompt
      del modo coche.

## Fase 1 — Fundamentos voz-primero en `commonMain` (1–2 semanas)

La app ya tiene TTS y reconocimiento de voz (`expect/actual` en `core/`), lo que adelanta
gran parte del trabajo.

- [ ] **Perfil de herramientas por contexto**: añadir un parámetro al tool loop de
      `SendMessageUseCase` (`UseCases.kt`) para filtrar el `ToolRegistry` según el contexto
      de la petición. En modo coche solo `search_web` tiene sentido; deshabilitar
      `generate_image`, `render_diagram`, filesystem tools, `run_command` y `manage_todos`.
- [ ] **`CarSessionManager`** en `commonMain`: sesión de chat dedicada ("modo coche") que
      reutiliza `SendMessageUseCase` con:
      - System prompt específico: respuestas en 1–2 frases, sin markdown, sin listas.
      - `max_tokens` bajo.
      - Perfil de herramientas "coche".
- [ ] **`CarMessageStore`** en `core/` (análogo a `ActiveSessionStore`): expone los mensajes
      del asistente como `Flow` para que las capas Android/iOS los conviertan en
      notificaciones/mensajes del sistema.
- [ ] **Modo respuesta completa**: en el coche no se renderiza token a token; acumular el
      streaming y emitir la respuesta completa para lectura por TTS.

## Fase 2 — Android Auto (`androidMain`) (2–3 semanas)

Mensajería en Android Auto = notificaciones `MessagingStyle`. No se necesita
`androidx.car.app` ni UI propia.

- [ ] **Manifest**: declarar soporte Auto en `AndroidManifest.xml`:
      - `<meta-data android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc"/>`
      - Crear `res/xml/automotive_app_desc.xml` con `<uses name="notification"/>`.
- [ ] **`CarNotificationService`**: observar `CarMessageStore` y publicar notificaciones
      `MessagingStyle` con:
      - Acción **Reply** con `RemoteInput` (respuesta por voz).
      - Acción **Mark as read** (obligatoria para Android Auto).
      - `CarAppExtender` (recomendado).
- [ ] **`BroadcastReceiver`s** de reply/read: el reply recibe el texto dictado y lo pasa a
      `SendMessageUseCase` a través de `ChatForegroundService` (ya existe y mantiene los
      streams vivos). La respuesta del modelo se publica como nueva notificación que Auto
      lee por TTS.
- [ ] **Pruebas con DHU** (Desktop Head Unit, en `sdk/extras/google/auto/`): ciclo completo
      voz → LLM → TTS desde el escritorio.
- [ ] Decisión consciente: **no** hacer UI con Car App Library (categorías POI/IoT) — un
      chatbot no pasaría revisión en esas categorías.

## Fase 3 — CarPlay / iOS (3–4 semanas, bloqueada por el entitlement)

La UI de mensajería en CarPlay la dibuja el sistema; la integración es vía SiriKit.

- [ ] Con el entitlement concedido, en el proyecto Xcode (`iosApp`):
      - Capability CarPlay (`com.apple.developer.carplay-communication`).
      - Crear una **Intents Extension** con soporte para `INSendMessageIntent` e
        `INSearchForMessagesIntent`.
- [ ] **Intent handlers en Swift**: el handler de `INSendMessageIntent` recibe el texto
      dictado por Siri y lo envía al LLM.
      - Decisión tomada: la extensión usa un **mini-cliente HTTP en Swift** (POST simple a
        `/v1/chat/completions`) en vez de compartir el framework KMP — las extensiones de
        Siri corren en proceso separado y esto es lo más simple. El tráfico de la extensión
        también pasa por ZeroTier (VPN del sistema).
      - Compartir `ConnectionConfig` (URL del servidor, modelo) con la extensión vía
        **App Group** (`UserDefaults(suiteName:)`).
- [ ] **Respuesta entrante**: publicar la respuesta del asistente como mensaje entrante
      (donación de intent + notificación de comunicación con `UNNotificationContent`) para
      que Siri/CarPlay la lea.
- [ ] **Pruebas con CarPlay Simulator**: Xcode → I/O → External Displays → CarPlay, y la app
      "CarPlay Simulator" de Additional Tools for Xcode.

## Fase 4 — Pruebas y endurecimiento (1–2 semanas)

- [ ] Pruebas en coche real o unidad aftermarket (Android Auto inalámbrico y CarPlay).
- [ ] **Medir latencia** del ciclo voz → LLM → TTS por LTE + ZeroTier. Si supera ~5–8 s,
      considerar un modelo más pequeño para el perfil coche.
- [ ] **Errores por voz**: si el servidor no responde, la notificación/Siri debe decir
      "no puedo conectar con tu servidor", nunca fallar en silencio.
- [ ] **Pérdida de red**: probar túneles/garajes; verificar que la VPN reconecta sola y que
      las peticiones en curso fallan con mensaje claro.

## Fase 5 — Distribución (2–4 semanas, mayormente espera)

- [ ] **Google Play**: declarar la app con soporte Android Auto categoría "mensajería" en
      Play Console y pasar la revisión específica de Auto (verifican reply/mark-as-read y
      ausencia de UI distractora). Nota: Android Auto no funciona con APKs sideloaded en
      producción, solo en modo desarrollador.
- [ ] **App Store**: revisión con el entitlement CarPlay activo; Apple verifica que solo se
      usen los intents permitidos.
