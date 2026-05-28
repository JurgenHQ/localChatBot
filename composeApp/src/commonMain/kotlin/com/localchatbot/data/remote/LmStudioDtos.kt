package com.localchatbot.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs de la API extendida de **LM Studio** (`/api/v0/...`). No forman parte del
 * estándar OpenAI: viven aparte para mantener [OpenAiDtos] limpio.
 */

@Serializable
data class LmStudioModelsResponse(
    val data: List<LmStudioModel> = emptyList()
)

@Serializable
data class LmStudioModel(
    val id: String,
    val state: String? = null,
    @SerialName("max_context_length") val maxContextLength: Int? = null,
    @SerialName("loaded_context_length") val loadedContextLength: Int? = null
)
