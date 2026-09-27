package com.localchatbot.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

class ActiveSessionStore {
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * Última foto que el usuario adjuntó a un mensaje, **por sesión**, seteada por
     * `SendMessageUseCase`. La usan `cartoonify_image`/`animate_image`/`cartoon_video` como
     * fallback cuando no hay una imagen generada por otra tool para encadenar.
     *
     * Va por sesión por el mismo motivo que [com.localchatbot.domain.tools.ProducedMediaSlot]:
     * con una tarea programada corriendo en paralelo al chat, un único slot global hacía que la
     * tarea "heredara" la foto que el usuario acababa de subir en otra conversación.
     */
    private val _lastUserImageBySession = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Mensaje al que el chat debe desplazarse en cuanto lo tenga en pantalla, publicado por
     * la búsqueda global al elegir un resultado.
     *
     * Existe como estado y no como parámetro de navegación porque seleccionar la sesión y
     * poder hacer scroll no ocurren a la vez: primero cambia `activeSessionId`, y sus mensajes
     * llegan después (la carga es reactiva). El chat lo consume cuando el mensaje ya existe
     * en la lista; hasta entonces queda pendiente.
     */
    private val _pendingScrollMessageId = MutableStateFlow<String?>(null)
    val pendingScrollMessageId: StateFlow<String?> = _pendingScrollMessageId.asStateFlow()

    fun set(id: String?) {
        _activeSessionId.value = id
    }

    /** Selecciona la sesión y deja pedido el scroll a [messageId] dentro de ella. */
    fun selectAndScrollTo(sessionId: String, messageId: String) {
        _pendingScrollMessageId.value = messageId
        _activeSessionId.value = sessionId
    }

    /** Lo llama el chat una vez hecho el scroll, para que no se repita al recomponer. */
    fun consumePendingScroll() {
        _pendingScrollMessageId.value = null
    }

    fun clearIfMatches(id: String) {
        if (_activeSessionId.value == id) _activeSessionId.value = null
    }

    /** Registra la foto adjuntada en [sessionId]; con `null` limpia la que hubiera. */
    fun setLastUserImage(sessionId: String, dataUrl: String?) {
        _lastUserImageBySession.update { current ->
            if (dataUrl == null) current - sessionId else current + (sessionId to dataUrl)
        }
    }

    /**
     * Última foto de la sesión del turno en curso ([TurnSessionContext]), o de la sesión visible
     * si se llama fuera de un turno. Es `suspend` porque la sesión sale del contexto de corutina.
     */
    suspend fun lastUserImageForTurn(): String? {
        val sessionId = coroutineContext[TurnSessionContext]?.sessionId
            ?: _activeSessionId.value
            ?: return null
        return _lastUserImageBySession.value[sessionId]
    }

    /** Olvida la foto de una sesión borrada, para no retener su base64 indefinidamente. */
    fun clearSessionState(sessionId: String) {
        _lastUserImageBySession.update { it - sessionId }
    }
}
