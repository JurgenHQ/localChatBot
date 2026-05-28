package com.localchatbot.domain.repository

import com.localchatbot.data.remote.ToolCall

/**
 * Eventos emitidos por el streaming del modelo cuando hay tool calling habilitado.
 */
sealed interface StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent
    data class Finish(
        val reason: String?,
        val toolCalls: List<ToolCall>
    ) : StreamEvent
}
