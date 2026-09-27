# Mostrar el razonamiento del modelo (chain-of-thought) en la UI

## Contexto

Modelos tipo deepseek-r1 / qwq / gemma emiten `reasoning_content` (pensamiento
previo a la respuesta). La infraestructura ya lo **captura, transmite y
persiste**:

- `StreamEvent.ReasoningDelta` (`StreamEvent.kt:15`)
- parseo en `OpenAiDtos.kt:121` + `ModelRepositoryImpl.kt:109-112`
- acumulación en `UseCases.kt` y persistencia vía `ChatMessage.reasoning`
  (`Chat.kt:75`) / `updateMessageReasoning` (`ChatRepositoryImpl.kt:140`)

**Pero nunca se renderiza** — el comentario en `Chat.kt:73` ya promete "un panel
collapsible" que no existe.

Objetivo (según diseños desktop + mobile): mostrar el razonamiento en 3 estados:

1. **Razonando (streaming)**: bloque "RAZONANDO •••" con el texto del pensamiento
   llegando en vivo (atenuado, con fade al fondo). Mientras aún no hay respuesta
   final.
2. **Listo · colapsado** (por defecto): chip "▸ Ver el razonamiento 3.2s" encima
   de la respuesta.
3. **Listo · expandido**: el chip se abre y muestra el texto del razonamiento.

La UI es 100% compartida → el mismo componente sirve desktop y mobile.

## Cambios

### 1. Duración real del razonamiento (capa de datos)

El chip muestra el tiempo de razonamiento (p.ej. "3.2s"). Hoy solo existe
`generationMs` (primer token → fin, incluye la respuesta). Capturar el tiempo de
*thinking* propio:

- **`ModelRepositoryImpl.kt`** (~L96-135): añadir `reasoningStartMs` (se fija en
  el primer `ReasoningDelta`) y `reasoningEndMs` (se fija en el primer
  `ContentDelta` posterior, cuando hay start y aún no end). En el `Finish`:
  `reasoningMs = reasoningStartMs?.let { (reasoningEndMs ?: now) - it }`.
- **`StreamEvent.kt`** `Finish`: nuevo campo `val reasoningMs: Long? = null`.
- **`Chat.kt`** `TokenMetrics`: nuevo campo `val reasoningMs: Long? = null`.
- **`UseCases.kt`** (loop de envío, bloque de métricas ~L183-192 y construcción
  de `TokenMetrics` ~L337-346): `var sumReasoningMs = 0L`,
  `event.reasoningMs?.let { sumReasoningMs += it }`, y
  `reasoningMs = sumReasoningMs.takeIf { it > 0 }`.

### 2. Nuevo componente `ReasoningPanel` (molecule)

Archivo nuevo: `presentation/components/molecules/ReasoningPanel.kt`.

```kotlin
@Composable
fun ReasoningPanel(
    reasoning: String,
    live: Boolean,
    durationMs: Long? = null,
    modifier: Modifier = Modifier
)
```

- **`live = true`** → Estado 1: encabezado "RAZONANDO" (labelSmall, monospace,
  `onSurfaceVariant`) + `TypingIndicator(dotSize = 6.dp)` al lado; debajo el texto
  del razonamiento (bodySmall, `onSurfaceVariant`) dentro de un `Box` con
  `heightIn(max ≈ 140.dp)` + `verticalScroll` auto-scrolleado al final + overlay
  de degradado (`Brush.verticalGradient` transparente→fondo) para el fade del
  diseño.
- **`live = false`** → Estados 2/3: chip clickable (Row con chevron
  `KeyboardArrowDown/Up` rotando vía `animateFloatAsState`, texto
  "Ver el razonamiento" + duración formateada) y
  `AnimatedVisibility(expandVertically()/shrinkVertically())` con el texto del
  razonamiento. `var expanded by remember { mutableStateOf(false) }` →
  **colapsado por defecto** (Estado 2).
- Helper local `formatDuration(ms): String` → "3.2s" (kotlin common no tiene
  `String.format`; calcular `((ms/100).toInt())/10.0` y componer manualmente).
- Estilo: fondo `surfaceVariant`, borde `outline`, `RoundedCornerShape(Radius.md)`,
  paddings `Spacing` — seguir el patrón de `TodoProgressPanel.kt` y
  `TerminalOutputBubble` (en `MessageBubble.kt:726`).
- Texto del razonamiento: `Text` plano seleccionable y atenuado (no Markdown) —
  coincide con el look "pensamiento interno" del diseño.

### 3. Integración en `MessageBubble.kt`

- **Firma**: añadir `isStreaming: Boolean = false` a `MessageBubble` y propagarlo
  a `AssistantBubble`.
- **Guard de visibilidad** (`MessageBubble.kt:127-129`): incluir reasoning para no
  ocultar la burbuja durante el Estado 1 (content vacío):
  `... || !message.reasoning.isNullOrBlank()`.
- **Render** en `AssistantBubble` (antes del bloque de `content`, ~L266):

```kotlin
message.reasoning?.takeIf { it.isNotBlank() }?.let {
    ReasoningPanel(
        reasoning = it,
        live = isStreaming && message.content.isBlank(),
        durationMs = message.metrics?.reasoningMs
    )
}
```

### 4. Threading del flag de streaming — `ChatMessageList.kt`

- Calcular el id del mensaje en vivo: si `sending` y el último visible es
  Assistant → ese id es "streaming". Pasar `isStreaming = (msg.id == ese id)` al
  `MessageBubble` (L88).
- **Evitar doble indicador**: en `showTyping` (L63-67) añadir
  `&& lastVisible.reasoning.isNullOrBlank()` a la rama del assistant con content
  vacío → cuando ya hay razonamiento en vivo se muestra el bloque RAZONANDO en
  lugar del `TypingIndicator` suelto.

## Archivos críticos

- `data/repository/ModelRepositoryImpl.kt` — medir `reasoningMs`.
- `domain/repository/StreamEvent.kt` — campo `reasoningMs` en `Finish`.
- `domain/model/Chat.kt` — campo `reasoningMs` en `TokenMetrics`.
- `domain/usecase/UseCases.kt` — acumular y adjuntar `reasoningMs`.
- `presentation/components/molecules/ReasoningPanel.kt` — **nuevo** componente.
- `presentation/components/molecules/MessageBubble.kt` — render + flag + guard.
- `presentation/components/organisms/ChatMessageList.kt` — flag streaming + ajuste
  de typing.

Reusar: `TypingIndicator` (atoms), patrón collapsible de `TodoProgressPanel.kt`,
tokens `Spacing`/`Radius` (`Dimens.kt`), `SelectableOnDesktop` (en MessageBubble).

## Verificación

1. Compilar: `./gradlew :composeApp:compileDebugKotlinAndroid`.
2. Desktop (`./gradlew :composeApp:run`) contra un modelo con reasoning
   (deepseek-r1 / qwq vía LM Studio o similar):
   - Enviar un prompt → confirmar **Estado 1**: aparece "RAZONANDO •••" con el
     texto del pensamiento en vivo y fade al fondo, sin doble typing indicator.
   - Al llegar la respuesta → el bloque colapsa al chip **Estado 2**
     "Ver el razonamiento Xs" sobre la respuesta.
   - Click en el chip → **Estado 3**: se expande y muestra el razonamiento;
     segundo click colapsa. Verificar la rotación del chevron y la animación.
   - Reabrir la sesión (persistencia): el chip sigue, con el texto y la duración.
3. Modelo SIN reasoning (p.ej. un llama normal) → no aparece chip ni bloque (no
   regresiones en el render normal).
4. (Opcional) Añadir un caso a `MoleculesPreviews.kt` con un `assistantMessage`
   que tenga `reasoning` para previsualizar los 3 estados.
