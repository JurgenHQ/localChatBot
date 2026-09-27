# Diseño: Cola de mensajes durante un turno en curso

**Fecha:** 2026-07-26
**Estado:** Aprobado

## Objetivo

Mientras el modelo está trabajando (streaming de la respuesta o bucle de tools, que puede durar minutos), el usuario puede seguir escribiendo y "enviando" mensajes. En vez de perderse, quedan **en cola** y se envían al terminar el turno. Mientras están en cola el usuario puede **eliminarlos** uno a uno.

Hoy no se puede: `ChatViewModel.send()` hace `return` en silencio si la sesión está streameando, y el composer ni siquiera muestra el botón de enviar — lo sustituye por Stop.

## Decisiones tomadas

- **Fusión en un solo mensaje**: al terminar el turno, todo lo encolado se une en **un** mensaje de usuario y el modelo responde una vez a todo junto (no un turno por mensaje).
- **Visible en el chat**: lo encolado se ve al final de la conversación, no escondido en un panel.
- **Solo en memoria**, por sesión: sobrevive a cambiar de conversación y volver; se pierde al cerrar la app. Sin tocar el esquema de SQLite (que además hoy no tiene migraciones funcionales).
- **Solo texto**: mientras hay turno en curso no se pueden adjuntar imágenes ni archivos.

## Componente nuevo: `QueuedMessageStore`

En `core/state/QueuedMessageStore.kt`, calcando el patrón de `PendingUserPromptStore` (estado por sesión, en memoria, expuesto como `StateFlow`):

```kotlin
data class QueuedMessage(val id: String, val text: String)

class QueuedMessageStore {
    val queued: StateFlow<Map<String, List<QueuedMessage>>>
    fun enqueue(sessionId: String, text: String)
    fun remove(sessionId: String, messageId: String)
    fun drain(sessionId: String): List<QueuedMessage>
    fun clear(sessionId: String)
    fun queueFor(sessionId: String?): List<QueuedMessage>
}
```

`drain` **debe ser atómico** (`MutableStateFlow.getAndUpdate`): devuelve la lista y la vacía en una sola operación, para que dos disparos concurrentes no puedan enviar la cola dos veces.

Se instancia en `AppContainer` junto a los otros stores de estado y se inyecta en `ChatViewModel`.

## Refactor de `ChatViewModel.send()`

`send()` mezcla hoy en ~70 líneas tres cosas: leer el composer, armar el mensaje (texto visible, data URL de la imagen, adjuntos, override de skill) y lanzar el turno. Se extrae la última parte:

```kotlin
private fun startTurn(
    sessionId: String?,
    text: String,
    dataUrl: String?,
    systemPromptOverride: String?,
    attachments: List<MessageAttachment>
)
```

Así el envío normal y el vaciado de la cola usan **la misma** ruta en vez de duplicar el lanzamiento del stream. Es el único refactor previo que entra en este cambio.

## Flujo

### Encolar

En `send()`, la guarda actual (`if (isStreaming) return`) pasa a encolar:

- Si hay turno en curso en la sesión activa y el texto no está en blanco → `queuedMessageStore.enqueue(...)` y se limpia el draft.
- Imagen y adjuntos no se encolan (sus botones están deshabilitados durante el turno).
- Texto vacío o solo espacios se ignora, igual que hoy.

### Vaciar

El vaciado ocurre **fuera del `finally`** que llama a `streamingStateStore.stop(sessionId)`, dentro de la misma corrutina de `streamJob`:

```kotlin
streamJob = applicationScope.launch {
    …
    try { … } finally { streamingStateStore.stop(sessionId); backgroundExecutor.stop() }
    drainQueueIfAny(sessionId, error)
}
```

**Ese orden es obligatorio.** Si el vaciado corriera dentro del `finally`, la sesión seguiría marcada como "streaming" y el mensaje fusionado volvería a encolarse a sí mismo, en bucle infinito.

Colocarlo después del `try/finally` da además el comportamiento correcto ante cancelación gratis: si el usuario pulsa Stop, la `CancellationException` propaga y el vaciado sencillamente no se ejecuta.

La fusión une los textos **en orden de encolado**, separados por una línea en blanco, y entra como un mensaje de usuario normal.

### Cuándo NO se vacía sola

La cola solo se vacía automáticamente tras un turno que termina **bien**. En los tres casos terminales restantes se queda quieta:

| Caso | Motivo |
|---|---|
| El usuario pulsó Stop | Acaba de frenar al modelo; lanzarle otro turno es lo contrario de lo que pidió |
| El turno falló | Debe ver el error y decidir, no encadenar sobre un fallo |
| El modelo preguntó vía `ask_user` | Lo encolado se escribió *antes* de existir la pregunta; enviarlo como respuesta sería contestar otra cosa |

Las tres condiciones se comprueban así: cancelación → el código tras el `finally` no llega a ejecutarse; fallo → `error != null`; pregunta pendiente → `pendingUserPromptStore.promptFor(sessionId) != null`.

Para que eso no deje estado sin salida, el contenedor de la cola muestra **"Enviar ahora"** cuando no hay turno en curso. Ese botón llama exactamente al mismo `drain` + `startTurn` que el vaciado automático, sin duplicar lógica. Siempre hay dos salidas: enviar o borrar.

## UI

### Chat — contenedor de encolados

Al final de la lista de mensajes, **un único contenedor punteado y atenuado** con las entradas encoladas dentro, cada una con su texto y una X para quitarla. Debajo, la leyenda *"Se enviarán juntos al terminar"*.

Se descartó pintar cada encolado como una burbuja fantasma independiente: se envían **fusionados**, así que tres burbujas sueltas que colapsan en una sola al terminar el turno sería engañoso. El contenedor agrupado enseña dónde van a caer sin mentir sobre cuántos mensajes son.

Cuando no hay turno en curso (stop, error o pregunta pendiente), el contenedor añade el botón **"Enviar ahora"**.

### Composer

Durante un turno en curso:

- El botón **Stop** se mantiene.
- Aparece a su lado un botón de **encolar** cuando hay texto escrito.
- Los botones de adjuntar imagen y archivo quedan **deshabilitados**.

## Casos borde

- **Borrar la sesión** limpia su cola (`SessionsViewModel.deleteSession`).
- **Cambiar de conversación**: la cola es por sesión; el contenedor sigue a su sesión y reaparece al volver.
- **Encolar en una sesión que deja de existir**: el vaciado **descarta** la cola en vez de crear una sesión nueva. `send()` sí crea una cuando la activa desapareció, porque ahí hay una intención explícita del usuario ahora mismo; resucitar una conversación borrada para volcarle mensajes viejos sería sorprendente.
- **Cola vacía**: `drainQueueIfAny` sale sin hacer nada; ningún turno extra.

## Sin cambios

- Esquema de SQLite: la cola nunca toca disco.
- `SendMessageUseCase`: recibe un mensaje de usuario normal, no sabe que viene de una cola.
- Acceso remoto: no expone la cola (es estado local de la UI de escritorio/móvil).

## Verificación

El proyecto no tiene tests automatizados, así que la verificación es manual salvo donde se indica:

1. Compilar los tres targets (`compileKotlinDesktop`, `compileKotlinIosSimulatorArm64`, `compileDebugKotlinAndroid`).
2. Con un turno largo en curso, encolar 3 mensajes → aparecen en el contenedor; al terminar llega **una** burbuja de usuario con los tres textos separados por línea en blanco.
3. Encolar 2, borrar el del medio → se envían solo los otros dos, en orden.
4. Encolar y pulsar **Stop** → la cola se mantiene, aparece "Enviar ahora", y pulsarlo envía el fusionado.
5. Encolar y provocar un fallo de conexión → la cola se mantiene con "Enviar ahora".
6. Encolar mientras el modelo llama a `ask_user` → al terminar el turno la cola NO se envía sola.
7. Encolar en la sesión A, cambiar a B (cola vacía) y volver a A → sigue ahí.
8. Encolar y borrar la sesión → sin fugas ni crash al volver.

`QueuedMessageStore` es una clase pura sin dependencias (ni red, ni UI, ni disco): es candidata natural a ser la primera con test unitario si se aborda el punto 1.2 del `ROADMAP.md`. La atomicidad de `drain` es lo que más merece esa cobertura.
