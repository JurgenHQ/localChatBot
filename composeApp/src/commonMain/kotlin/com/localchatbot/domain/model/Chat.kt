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
    val reasoning: String? = null
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
