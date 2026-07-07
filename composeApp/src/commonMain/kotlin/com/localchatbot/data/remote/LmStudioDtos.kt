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
    val type: String? = null,
    val state: String? = null,
    @SerialName("max_context_length") val maxContextLength: Int? = null,
    @SerialName("loaded_context_length") val loadedContextLength: Int? = null
)

// --- API nativa v1 (LM Studio >= 0.4.0): lista completa + load/unload ---

@Serializable
data class LmStudioV1ModelsResponse(
    val models: List<LmStudioV1Model> = emptyList()
)

@Serializable
data class LmStudioV1Model(
    val key: String,
    val type: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("params_string") val paramsString: String? = null,
    @SerialName("max_context_length") val maxContextLength: Int? = null,
    @SerialName("loaded_instances") val loadedInstances: List<LmStudioV1Instance> = emptyList()
)

@Serializable
data class LmStudioV1Instance(
    val id: String,
    val config: LmStudioV1InstanceConfig? = null
)

@Serializable
data class LmStudioV1InstanceConfig(
    @SerialName("context_length") val contextLength: Int? = null
)

@Serializable
data class LmStudioLoadRequest(
    val model: String
)

@Serializable
data class LmStudioLoadResponse(
    @SerialName("instance_id") val instanceId: String,
    val status: String? = null
)

@Serializable
data class LmStudioUnloadRequest(
    @SerialName("instance_id") val instanceId: String
)
