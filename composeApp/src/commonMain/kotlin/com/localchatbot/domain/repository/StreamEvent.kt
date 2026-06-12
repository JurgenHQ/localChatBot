package com.localchatbot.domain.repository

import com.localchatbot.data.remote.ToolCall

/**
 * Eventos emitidos por el streaming del modelo cuando hay tool calling habilitado.
 */
sealed interface StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent
    /**
     * Razonamiento (chain-of-thought) emitido por modelos como Gemma 3/4, QwQ,
     * DeepSeek-R1 o tipo o1. Es lo que el modelo "piensa" antes de responder.
     * La UI puede mostrarlo en un panel collapsible separado del contenido final.
     */
    data class ReasoningDelta(val text: String) : StreamEvent
    data class Finish(
        val reason: String?,
        val toolCalls: List<ToolCall>,
        val actualModel: String? = null,
        /** Tokens del prompt (input). Null si el servidor no reportó `usage`. */
        val inputTokens: Int? = null,
        /** Tokens de la respuesta (output). Estimado si el servidor no reportó `usage`. */
        val outputTokens: Int? = null,
        /** Tiempo de generación en ms (del primer token al final), para tokens/s. */
        val generationMs: Long? = null,
        /** True si los tokens son una estimación (no vinieron del servidor). */
        val estimated: Boolean = false
    ) : StreamEvent
}
