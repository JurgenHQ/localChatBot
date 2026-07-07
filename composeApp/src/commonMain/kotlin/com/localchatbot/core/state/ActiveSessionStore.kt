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

    fun set(id: String?) {
        _activeSessionId.value = id
    }

    fun clearIfMatches(id: String) {
        if (_activeSessionId.value == id) _activeSessionId.value = null
    }

    fun setLastUserImage(dataUrl: String?) {
        _lastUserImageDataUrl.value = dataUrl
    }
}
