package com.localchatbot.data.remote

import kotlinx.serialization.Serializable

/**
 * DTOs del servicio de generación de imágenes (FastAPI wrapper sobre ComfyUI).
 * Endpoint: `POST <baseUrl>/generate-image`.
 */

@Serializable
data class ImageGenRequest(
    val prompt: String,
    val negative_prompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Double? = null,
    val seed: Long? = null
)

@Serializable
data class ImageGenResponse(
    val success: Boolean,
    val image_base64: String? = null,
    val image_path: String? = null,
    val filename: String? = null,
    val seed: Long? = null,
    val error: String? = null
)
