package com.localchatbot.domain.tools

import com.localchatbot.data.remote.AnimateRequest
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.data.remote.VideoGenApi
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que anima una imagen fija convirtiéndola en un clip de video corto (SVD,
 * `POST /animate`). Necesita una imagen de entrada, resuelta vía [sourceImageProvider]
 * igual que [CartoonTool]. Produce VIDEO out-of-band (no imagen) — ver `consumeProducedVideo`
 * en [Tool].
 */
class AnimateTool(
    private val api: VideoGenApi,
    private val preferences: PreferencesRepository,
    private val json: Json,
    private val sourceImageProvider: () -> String?
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Animando imagen… (puede tardar 1-3 minutos)"

    override suspend fun isAvailable(): Boolean =
        preferences.current().effectiveImageServiceUrl.isNotBlank()

    private val _lastVideo = MutableStateFlow<String?>(null)

    private fun consumeLastVideo(): String? {
        val v = _lastVideo.value
        _lastVideo.value = null
        return v
    }

    override fun consumeProducedVideo(): String? = consumeLastVideo()

    override fun peekProducedVideo(): String? = _lastVideo.value

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Animates a still image into a short video clip (Stable Video Diffusion). " +
                "Uses the most recently uploaded photo or generated/cartoonified image automatically " +
                "— no image parameter needed. Takes ~1-3 minutes: tell the user upfront it will take " +
                "a while. `motion_bucket_id` (1-255) controls how much motion appears — higher is more " +
                "movement, default 127. Width/height are auto-detected from the source photo's " +
                "orientation, do not set them. Do NOT include the base64 in your reply.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("frames", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of frames, ~14-25. Default 25.")
                    })
                    put("fps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Frames per second of the output clip. Default 6.")
                    })
                    put("motion_bucket_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Motion intensity, 1-255. Higher = more movement. Default 127.")
                    })
                })
                put("required", buildJsonArray {})
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return errorPayload("Arguments JSON inválido: ${it.message}") }

        val baseUrl = preferences.current().effectiveImageServiceUrl
        if (baseUrl.isBlank()) {
            return errorPayload(
                "MISSING_IMAGE_SERVICE. Responde al usuario, en su mismo idioma y sin añadir " +
                    "información extra, con este mensaje: \"Para generar video, configura la URL " +
                    "del servicio en Configuración → Generación de imágenes.\""
            )
        }

        val sourceDataUrl = sourceImageProvider()
            ?: return errorPayload(
                "NO_SOURCE_IMAGE. No hay ninguna imagen disponible para animar. Pide al usuario " +
                    "que suba una foto, o genera/cartoonifica una imagen primero."
            )
        val sourceBase64 = sourceDataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (sourceBase64.isBlank()) {
            return errorPayload("La imagen de origen disponible no tiene datos base64 válidos.")
        }

        val request = AnimateRequest(
            image_base64 = sourceBase64,
            frames = args["frames"]?.jsonPrimitive?.intOrNull,
            fps = args["fps"]?.jsonPrimitive?.intOrNull,
            motion_bucket_id = args["motion_bucket_id"]?.jsonPrimitive?.intOrNull
        )

        return api.animate(baseUrl, request).fold(
            onSuccess = { response ->
                if (!response.success) {
                    return@fold errorPayload(response.error ?: "El servicio devolvió success=false")
                }
                response.video_base64?.let { b64 ->
                    _lastVideo.value = "data:video/mp4;base64,$b64"
                }
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("success", true)
                        response.filename?.let { put("filename", it) }
                        response.seed?.let { put("seed", it) }
                        put("note", "Video generated and shown to the user. Do not echo the base64.")
                    }
                )
            },
            onFailure = { e -> errorPayload(e.message ?: "Error de red contra el Image Service") }
        )
    }

    private fun errorPayload(message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", false)
                put("error", message)
            }
        )

    companion object {
        const val TOOL_NAME = "animate_image"
    }
}
