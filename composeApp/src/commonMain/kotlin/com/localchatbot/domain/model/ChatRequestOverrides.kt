package com.localchatbot.domain.model

/**
 * Overrides por invocación para [com.localchatbot.domain.usecase.SendMessageUseCase].
 * Permiten que contextos especiales (modo coche) cambien el comportamiento sin
 * tocar las preferencias globales del usuario.
 *
 * - [systemPrompt]: reemplaza POR COMPLETO el system prompt (incluido el agent
 *   prompt de tools). El contexto que lo use debe describir sus propias tools.
 * - [maxTokens]: límite de tokens de respuesta enviado como `max_tokens`.
 * - [allowedToolNames]: si no es null, solo estas tools se ofrecen al modelo.
 */
data class ChatRequestOverrides(
    val systemPrompt: String? = null,
    val maxTokens: Int? = null,
    val allowedToolNames: Set<String>? = null
)
