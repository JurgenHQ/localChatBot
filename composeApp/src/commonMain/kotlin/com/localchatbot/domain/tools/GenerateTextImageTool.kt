package com.localchatbot.domain.tools

import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ImageGenApi
import com.localchatbot.data.remote.TextImageGenRequest
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Tool que genera una imagen con **texto legible** dentro (SD 3.5, `POST /generate-text-image`).
 * A diferencia de `generate_image` (SDXL), este modelo sí renderiza palabras/carteles/logos
 * correctamente. Es notablemente más lento (~150-200s) — el modelo debe avisar al usuario.
 *
 * Mismo patrón out-of-band que [ImageGenerationTool]: el base64 nunca vuelve al contexto del
 * modelo, solo metadatos.
 */
class GenerateTextImageTool(
    private val api: ImageGenApi,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Generando imagen con texto… (puede tardar unos minutos)"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["text"]?.jsonPrimitive?.content
            ?: json.parseToJsonElement(argumentsJson).jsonObject["prompt"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean =
        preferences.current().effectiveImageServiceUrl.isNotBlank()

    private val _lastImage = MutableStateFlow<String?>(null)

    private fun consumeLastImage(): String? {
        val v = _lastImage.value
        _lastImage.value = null
        return v
    }

    override fun consumeProducedImage(): String? = consumeLastImage()

    override fun peekProducedImage(): String? = _lastImage.value

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Generates an image with LEGIBLE TEXT rendered inside it (posters, signs, " +
                "logos, banners with words). Use this instead of `generate_image` whenever the user " +
                "needs readable words/letters in the picture — SDXL (`generate_image`) cannot render " +
                "text correctly. Keep the requested text SHORT (1-3 words) for best accuracy — longer " +
                "text is more likely to have typos. This endpoint is SLOW (~150-200 seconds): tell the " +
                "user upfront that it will take a couple of minutes before calling it. Do NOT include " +
                "the base64 in your reply.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Scene/image description in English.")
                    })
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "The exact word or short phrase to render legibly in the image (1-3 words recommended).")
                    })
                    put("negative_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Elements to avoid.")
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

        val request = TextImageGenRequest(
            prompt = prompt,
            text = args["text"]?.jsonPrimitive?.content,
            negative_prompt = args["negative_prompt"]?.jsonPrimitive?.content,
            width = args["width"]?.jsonPrimitive?.intOrNull,
            height = args["height"]?.jsonPrimitive?.intOrNull
        )

        return api.generateTextImage(baseUrl, request).fold(
            onSuccess = { response ->
                if (!response.success) {
                    return@fold errorPayload(response.error ?: "El servicio devolvió success=false")
                }
                response.image_base64?.let { b64 ->
                    _lastImage.value = "data:image/png;base64,$b64"
                }
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
        const val TOOL_NAME = "generate_text_image"
    }
}
