package com.localchatbot.domain.tools

import com.localchatbot.data.remote.CartoonVideoRequest
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.data.remote.VideoGenApi
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que encadena el pipeline completo foto → caricatura → video en una sola llamada
 * (`POST /cartoon-video`, combina SDXL + SVD server-side). Alternativa más rápida a llamar
 * `cartoonify_image` seguido de `animate_image` por separado. Necesita imagen de entrada,
 * resuelta vía [sourceImageProvider] igual que [CartoonTool]/[AnimateTool]. Produce VIDEO
 * out-of-band.
 */
class CartoonVideoTool(
    private val api: VideoGenApi,
    private val preferences: PreferencesRepository,
    private val json: Json,
    private val sourceImageProvider: () -> String?
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Generando video de caricatura… (puede tardar varios minutos)"

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
            description = "Full pipeline: turns a photo into a cartoon-style animated video clip in " +
                "one call (cartoonify + animate combined server-side). Prefer this over calling " +
                "`cartoonify_image` then `animate_image` separately when the user wants the final " +
                "video directly. Uses the most recently uploaded photo automatically — no image " +
                "parameter needed. Takes several minutes: tell the user upfront. Do NOT include the " +
                "base64 in your reply.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("cartoon_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Desired cartoon style, in English.")
                    })
                    put("cartoon_denoise", buildJsonObject {
                        put("type", "number")
                        put("description", "0.0-1.0. 0.45 preserves likeness, 0.6+ more stylized. Default 0.5.")
                    })
                    put("frames", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of video frames. Default 25.")
                    })
                    put("motion_bucket_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Motion intensity, 1-255. Default 127.")
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
                "NO_SOURCE_IMAGE. No hay ninguna foto disponible. Pide al usuario que suba una foto."
            )
        val sourceBase64 = sourceDataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (sourceBase64.isBlank()) {
            return errorPayload("La imagen de origen disponible no tiene datos base64 válidos.")
        }

        val request = CartoonVideoRequest(
            image_base64 = sourceBase64,
            cartoon_prompt = args["cartoon_prompt"]?.jsonPrimitive?.content,
            cartoon_denoise = args["cartoon_denoise"]?.jsonPrimitive?.doubleOrNull,
            frames = args["frames"]?.jsonPrimitive?.intOrNull,
            motion_bucket_id = args["motion_bucket_id"]?.jsonPrimitive?.intOrNull
        )

        return api.cartoonVideo(baseUrl, request).fold(
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
        const val TOOL_NAME = "cartoon_video"
    }
}
