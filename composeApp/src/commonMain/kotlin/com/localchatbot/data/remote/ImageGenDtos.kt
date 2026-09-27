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

/**
 * Request de `POST /generate-text-image` (SD 3.5). Único modelo local capaz de renderizar
 * texto legible dentro de la imagen (SDXL produce texto ilegible). Responde con [ImageGenResponse]
 * (mismo contrato que `/generate-image`).
 */
@Serializable
data class TextImageGenRequest(
    val prompt: String,
    val text: String? = null,
    val negative_prompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Double? = null,
    val seed: Long? = null
)

/**
 * Request de `POST /cartoon` (SDXL img2img, foto → caricatura). Responde con [ImageGenResponse].
 */
@Serializable
data class CartoonRequest(
    val image_base64: String,
    val prompt: String? = null,
    val negative_prompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Double? = null,
    val denoise: Double? = null,
    val seed: Long? = null
)
