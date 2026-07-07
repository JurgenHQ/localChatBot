package com.localchatbot.data.remote

import kotlinx.serialization.Serializable

/**
 * DTOs del Video Service (mismo FastAPI wrapper sobre ComfyUI que `ImageGenApi`, endpoints
 * `/animate` y `/cartoon-video`). El contrato de respuesta es análogo a [ImageGenResponse] pero
 * con `video_base64`/`video_path` en vez de `image_base64`/`image_path`.
 */

@Serializable
data class AnimateRequest(
    val image_base64: String,
    val frames: Int? = null,
    val fps: Int? = null,
    /** Null = auto-orientación según la foto (vertical/horizontal/cuadrada). */
    val width: Int? = null,
    val height: Int? = null,
    val motion_bucket_id: Int? = null,
    val augmentation_level: Double? = null,
    val steps: Int? = null,
    val seed: Long? = null
)

@Serializable
data class CartoonVideoRequest(
    val image_base64: String,
    val cartoon_prompt: String? = null,
    val cartoon_negative: String? = null,
    val cartoon_denoise: Double? = null,
    val frames: Int? = null,
    val fps: Int? = null,
    val motion_bucket_id: Int? = null,
    val steps_image: Int? = null,
    val steps_video: Int? = null,
    val seed: Long? = null
)

@Serializable
data class VideoGenResponse(
    val success: Boolean,
    val video_base64: String? = null,
    val video_path: String? = null,
    val filename: String? = null,
    val seed: Long? = null,
    val error: String? = null
)
