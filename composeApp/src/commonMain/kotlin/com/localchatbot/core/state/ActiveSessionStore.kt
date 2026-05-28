package com.localchatbot.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveSessionStore {
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    fun set(id: String?) {
        _activeSessionId.value = id
    }

    fun clearIfMatches(id: String) {
        if (_activeSessionId.value == id) _activeSessionId.value = null
    }
}
