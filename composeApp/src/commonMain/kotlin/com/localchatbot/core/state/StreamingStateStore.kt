package com.localchatbot.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks which sessions currently have an in-flight streaming request and,
 * when a tool is being executed, qué tool y con qué detalle (para feedback en UI).
 *
 * Lives at the application scope so its state survives ViewModel recreation
 * (rotation, low-memory recreation, returning from background).
 */
class StreamingStateStore {
    private val _streaming = MutableStateFlow<Set<String>>(emptySet())
    val streaming: StateFlow<Set<String>> = _streaming.asStateFlow()

    private val _activity = MutableStateFlow<Map<String, ToolActivity>>(emptyMap())
    val activity: StateFlow<Map<String, ToolActivity>> = _activity.asStateFlow()

    private val _toolCallLog = MutableStateFlow<Map<String, List<ToolActivity>>>(emptyMap())
    val toolCallLog: StateFlow<Map<String, List<ToolActivity>>> = _toolCallLog.asStateFlow()

    fun start(sessionId: String) {
        _streaming.update { it + sessionId }
        _toolCallLog.update { it + (sessionId to emptyList()) }
    }

    fun stop(sessionId: String) {
        _streaming.update { it - sessionId }
        clearActivity(sessionId)
        _toolCallLog.update { it - sessionId }
    }

    fun isStreaming(sessionId: String): Boolean = _streaming.value.contains(sessionId)

    fun markActivity(sessionId: String, label: String, detail: String?) {
        val entry = ToolActivity(label, detail)
        _activity.update { it + (sessionId to entry) }
        _toolCallLog.update { map ->
            val existing = map.getOrElse(sessionId) { emptyList() }
            map + (sessionId to (existing + entry))
        }
    }

    fun clearActivity(sessionId: String) =
        _activity.update { it - sessionId }
}

data class ToolActivity(
    val label: String,
    val detail: String?
)
