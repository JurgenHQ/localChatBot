package com.localchatbot.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

enum class Role { User, Assistant, System, Tool }

@Serializable
data class PersistedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

@Serializable
data class WebSource(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Métricas de generación de una respuesta del modelo. [inputTokens] viene del
 * servidor (`usage.prompt_tokens`); [outputTokens] del servidor o estimado por
 * longitud cuando [estimated] es true.
 */
@Serializable
data class TokenMetrics(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val generationMs: Long? = null,
    val estimated: Boolean = false,
    /**
     * Tamaño real del contexto = `prompt_tokens` de la ÚLTIMA llamada al modelo.
     * A diferencia de [inputTokens] (que suma todas las rondas de tool-calls para el
     * coste), esto refleja cuánto ocupa la ventana de contexto ahora mismo. Lo usa la
     * barra de contexto del top bar.
     */
    val contextTokens: Int? = null
) {
    val totalTokens: Int?
        get() = when {
            inputTokens != null && outputTokens != null -> inputTokens + outputTokens
            inputTokens != null -> inputTokens
            outputTokens != null -> outputTokens
            else -> null
        }

    val tokensPerSecond: Double?
        get() = if (outputTokens != null && generationMs != null && generationMs > 0)
            outputTokens / (generationMs / 1000.0) else null
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestampEpochMs: Long,
    /** Full data URL: `data:image/jpeg;base64,XXXX`. Solo en mensajes del usuario. */
    val imageDataUrl: String? = null,
    /** Tool calls emitidos por el assistant (cuando finish_reason="tool_calls"). */
    val toolCalls: List<PersistedToolCall>? = null,
    /** Solo en role=Tool: id del tool_call al que responde. */
    val toolCallId: String? = null,
    /** Solo en role=Tool: nombre de la tool ejecutada, p.ej. "search_web". */
    val toolName: String? = null,
    /** Solo en role=Assistant final: fuentes web a renderizar como chips. */
    val sources: List<WebSource>? = null,
    /**
     * Razonamiento (chain-of-thought) emitido por modelos como Gemma 3/4, QwQ,
     * DeepSeek-R1 o tipo o1. NO se envía de vuelta al modelo en siguientes turnos
     * (es contenido interno). La UI lo muestra en un panel collapsible aparte.
     */
    val reasoning: String? = null,
    /** Métricas de tokens/velocidad de la respuesta. Solo en role=Assistant final. */
    val metrics: TokenMetrics? = null
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val model: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<ChatMessage>,
    val pinned: Boolean = false
) {
    val lastMessagePreview: String?
        get() = messages.lastOrNull { it.role != Role.Tool }?.content?.take(80)

    val updatedAt: Instant get() = Instant.fromEpochMilliseconds(updatedAtEpochMs)
}
