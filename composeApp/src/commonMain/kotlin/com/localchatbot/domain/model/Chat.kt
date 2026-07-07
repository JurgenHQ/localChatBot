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
 * Archivo de texto adjuntado por el usuario a un mensaje. El contenido se inyecta
 * en el payload de la API (como bloque fenced) para que el modelo lo lea, pero NO
 * se muestra crudo en la burbuja del chat: la UI solo enseña un chip con el nombre.
 */
@Serializable
data class MessageAttachment(
    val name: String,
    val content: String
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
    val contextTokens: Int? = null,
    /** Duración del bloque de razonamiento (thinking) en ms. Null si el modelo no emitió reasoning. */
    val reasoningMs: Long? = null
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
    /** Full data URL: `data:video/mp4;base64,XXXX`. Video generado out-of-band por `animate`/`cartoon_video`. */
    val videoDataUrl: String? = null,
    /**
     * Archivos de texto adjuntados (solo en role=User). Se expanden a bloques fenced
     * dentro del contenido enviado al modelo (ver `buildMessagesForApi`), pero no se
     * renderizan crudos en la burbuja: la UI muestra un chip por archivo.
     */
    val attachments: List<MessageAttachment>? = null,
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
    val metrics: TokenMetrics? = null,
    /**
     * Solo en el mensaje assistant que anunció tool_calls de mutación de archivos:
     * id del checkpoint del turno (= id del user message del turno). La UI muestra
     * el chip "revertir este turno" cuando no es null. Desktop only.
     */
    val checkpointId: String? = null
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val model: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<ChatMessage>,
    val pinned: Boolean = false,
    /** Override de parámetros de generación para esta sesión. Null = usar los globales. */
    val generationParams: GenerationParams? = null,
    /**
     * Resumen rodante de los mensajes descartados al superar el presupuesto de contexto.
     * Se genera en background y se inyecta como system message al inicio del historial
     * para mantener coherencia tras el truncado. Null = sin truncación todavía.
     */
    val contextSummary: String? = null
) {
    val lastMessagePreview: String?
        get() = messages.lastOrNull { it.role != Role.Tool }?.content?.take(80)

    val updatedAt: Instant get() = Instant.fromEpochMilliseconds(updatedAtEpochMs)
}
