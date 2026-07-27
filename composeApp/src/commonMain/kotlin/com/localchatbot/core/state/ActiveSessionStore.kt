package com.localchatbot.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveSessionStore {
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * Última foto que el usuario adjuntó a un mensaje, seteada por `SendMessageUseCase`.
     * La usan `cartoonify_image`/`animate_image`/`cartoon_video` como fallback cuando no hay una
     * imagen generada por otra tool para encadenar.
     */
    private val _lastUserImageDataUrl = MutableStateFlow<String?>(null)
    val lastUserImageDataUrl: StateFlow<String?> = _lastUserImageDataUrl.asStateFlow()

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

    fun setLastUserImage(dataUrl: String?) {
        _lastUserImageDataUrl.value = dataUrl
    }
}
