package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GenerationParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Int? = null
)
