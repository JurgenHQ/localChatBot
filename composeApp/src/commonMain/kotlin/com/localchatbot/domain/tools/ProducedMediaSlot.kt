package com.localchatbot.domain.tools

import com.localchatbot.core.state.TurnSessionContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

/**
 * Slot de media "out of band" (imagen o video) producida por una tool, **con clave por sesión**.
 *
 * Las tools son singletons del [ToolRegistry], y las de MCP las cachea `McpToolProvider` por
 * servidor, así que un único campo `_lastImage` era estado GLOBAL compartido por todos los
 * turnos. Con una tarea programada corriendo en paralelo al chat interactivo (el mutex del
 * scheduler serializa las tareas entre sí, no contra el chat) el drenaje de fin de turno se
 * llevaba la media del OTRO turno: la captura de la tarea aparecía en la conversación que el
 * usuario tenía abierta y no en la suya.
 *
 * La sesión se resuelve por [TurnSessionContext], que `SendMessageUseCase` instala alrededor
 * del turno entero, así que producir y drenar caen siempre en la misma clave aunque el usuario
 * navegue a otra conversación mientras el turno corre.
 *
 * Nota: si un turno se cancela antes de drenar, su entrada queda hasta el siguiente turno *de
 * esa misma sesión*, que la adjuntará. Es el mismo comportamiento que antes, pero ahora acotado
 * a la conversación que la produjo en vez de filtrarse a cualquier otra.
 */
class ProducedMediaSlot {

    private val bySession = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Guarda la media producida por el turno en curso. */
    suspend fun set(dataUrl: String) {
        val key = currentKey()
        bySession.update { it + (key to dataUrl) }
    }

    /** Media del turno en curso SIN consumirla (la usan `save_image` / `save_video`). */
    suspend fun peek(): String? = bySession.value[currentKey()]

    /** Media del turno en curso, borrándola del slot. */
    suspend fun consume(): String? {
        val key = currentKey()
        var found: String? = null
        bySession.update { current ->
            found = current[key]
            if (found == null) current else current - key
        }
        return found
    }

    /**
     * Fuera de un turno (no debería pasar en producción: toda ejecución de tool va dentro del
     * `withContext(TurnSessionContext)` del use case) se cae a una clave vacía compartida, que
     * preserva el comportamiento anterior en vez de perder la media.
     */
    private suspend fun currentKey(): String =
        coroutineContext[TurnSessionContext]?.sessionId ?: ""
}
