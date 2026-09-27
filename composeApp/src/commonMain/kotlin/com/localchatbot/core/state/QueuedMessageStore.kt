package com.localchatbot.core.state

import com.localchatbot.core.util.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** Un mensaje escrito por el usuario mientras el modelo trabajaba, aún sin enviar. */
data class QueuedMessage(
    val id: String,
    val text: String
)

/**
 * Mensajes que el usuario escribió con un turno en curso y que se enviarán **fusionados en
 * uno solo** cuando el turno termine bien. Hasta entonces puede quitarlos uno a uno.
 *
 * Estado por sesión y en memoria, igual que [PendingUserPromptStore] y
 * [StreamingStateStore]: sobrevive a cambiar de conversación y volver, pero no a cerrar la
 * app. La cola nunca toca disco — no hay tabla ni clave en settings.
 */
class QueuedMessageStore {
    private val _queued = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())
    val queued: StateFlow<Map<String, List<QueuedMessage>>> = _queued.asStateFlow()

    /** Encola [text] al final. El texto en blanco se ignora (mismo criterio que `send()`). */
    fun enqueue(sessionId: String, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        _queued.update { map ->
            map + (sessionId to map.getOrElse(sessionId) { emptyList() } + QueuedMessage(newId(), cleaned))
        }
    }

    fun remove(sessionId: String, messageId: String) {
        _queued.update { map ->
            val remaining = map.getOrElse(sessionId) { emptyList() }.filterNot { it.id == messageId }
            if (remaining.isEmpty()) map - sessionId else map + (sessionId to remaining)
        }
    }

    /**
     * Devuelve la cola y la vacía **en una sola operación**. La atomicidad no es adorno: el
     * vaciado puede dispararse desde el fin de un turno y desde el botón "Enviar ahora", y
     * sin esto dos disparos concurrentes leerían la misma lista y la enviarían dos veces.
     */
    fun drain(sessionId: String): List<QueuedMessage> =
        _queued.getAndUpdate { it - sessionId }.getOrElse(sessionId) { emptyList() }

    fun clear(sessionId: String) = _queued.update { it - sessionId }

    fun queueFor(sessionId: String?): List<QueuedMessage> =
        if (sessionId == null) emptyList() else _queued.value[sessionId].orEmpty()
}
