package com.localchatbot.domain.tools

import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ImageGenApi
import com.localchatbot.data.remote.ImageGenRequest
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que genera imágenes vía el Image Service local (FastAPI + ComfyUI/SDXL).
 *
 * IMPORTANTE sobre el manejo del base64: el modelo recibe en `role=tool` SOLO metadatos
 * (filename, seed, prompt) — el blob base64 NO se devuelve como contenido del tool message,
 * porque se traduciría a decenas de miles de tokens basura en el contexto en la siguiente
 * iteración. En su lugar lo exponemos en [lastGeneratedImageDataUrl] para que
 * `SendMessageUseCase` lo recoja y lo adjunte al `ChatMessage` final del assistant.
 */
class ImageGenerationTool(
    private val api: ImageGenApi,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Generando imagen…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["prompt"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean =
        preferences.current().effectiveImageServiceUrl.isNotBlank()

    private val _lastImage = MutableStateFlow<String?>(null)
    val lastGeneratedImageDataUrl: StateFlow<String?> = _lastImage

    fun consumeLastImage(): String? {
        val v = _lastImage.value
        _lastImage.value = null
        return v
    }

    override fun consumeProducedImage(): String? = consumeLastImage()

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Generates an image from a text description. Call this when the user " +
                "asks to create, draw, generate, visualize or see an image of something. " +
                "Translate the user's description into a detailed, vivid English prompt suitable " +
                "for SDXL. After the image is generated, briefly tell the user the image is ready, " +
                "in their language. Do NOT include the base64 in your reply.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed image description in English, optimized for SDXL.")
                    })
                    put("negative_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Elements to avoid (e.g. \"blurry, watermark, ugly\").")
                    })
                    put("width", buildJsonObject {
                        put("type", "integer")
                        put("description", "Width in pixels, default 1024.")
                    })
                    put("height", buildJsonObject {
                        put("type", "integer")
                        put("description", "Height in pixels, default 1024.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("prompt")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return errorPayload("Arguments JSON inválido: ${it.message}") }

        val prompt = args["prompt"]?.jsonPrimitive?.content
            ?: return errorPayload("Argumento 'prompt' faltante")

        val baseUrl = preferences.current().effectiveImageServiceUrl
        if (baseUrl.isBlank()) {
            return errorPayload(
                "MISSING_IMAGE_SERVICE. Responde al usuario, en su mismo idioma y sin añadir " +
                    "información extra, con este mensaje: \"Para generar imágenes, configura la " +
                    "URL del servicio en Configuración → Generación de imágenes.\""
            )
        }

        val request = ImageGenRequest(
            prompt = prompt,
            negative_prompt = args["negative_prompt"]?.jsonPrimitive?.content,
            width = args["width"]?.jsonPrimitive?.intOrNull,
            height = args["height"]?.jsonPrimitive?.intOrNull
        )

        return api.generate(baseUrl, request).fold(
            onSuccess = { response ->
                if (!response.success) {
                    return@fold errorPayload(response.error ?: "El servicio devolvió success=false")
                }
                response.image_base64?.let { b64 ->
                    _lastImage.value = "data:image/png;base64,$b64"
                }
                // Devolver al modelo SOLO metadatos. El base64 queda fuera del contexto.
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("success", true)
                        put("prompt", prompt)
                        response.filename?.let { put("filename", it) }
                        response.seed?.let { put("seed", it) }
                        put("note", "Image generated and shown to the user. Do not echo the base64.")
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
        const val TOOL_NAME = "generate_image"
    }
}
