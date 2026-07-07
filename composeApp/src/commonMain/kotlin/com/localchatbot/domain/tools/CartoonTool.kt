package com.localchatbot.domain.tools

import com.localchatbot.data.remote.CartoonRequest
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ImageGenApi
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que convierte una foto en una ilustración estilo caricatura (SDXL img2img,
 * `POST /cartoon`). Necesita una imagen de entrada, que resuelve vía [sourceImageProvider]:
 * la última imagen generada por otra tool (encadenable) o la última foto que subió el usuario.
 *
 * Mismo patrón out-of-band que [ImageGenerationTool] para la imagen de salida.
 */
class CartoonTool(
    private val api: ImageGenApi,
    private val preferences: PreferencesRepository,
    private val json: Json,
    /** Data URL de la imagen de origen (peek, sin consumir), o null si no hay ninguna disponible. */
    private val sourceImageProvider: () -> String?
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Convirtiendo en caricatura…"

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
            description = "Converts a photo into a cartoon-style illustration (SDXL img2img). Uses " +
                "the most recently uploaded photo or generated image automatically — no image " +
                "parameter needed, just call this after the user shares a photo or asks to " +
                "cartoonify one. `denoise` controls how much the result deviates from the original: " +
                "0.45 preserves likeness, 0.6+ is more stylized but loses resemblance (default 0.5). " +
                "The current SDXL-based effect is a soft illustration, not an exaggerated caricature — " +
                "mention that if the user expects a strong caricature look. Do NOT include the base64 " +
                "in your reply.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Desired cartoon style, in English. Defaults to a flat cel-shaded 2D cartoon style if omitted.")
                    })
                    put("denoise", buildJsonObject {
                        put("type", "number")
                        put("description", "0.0-1.0. 0.45 preserves likeness, 0.6+ more stylized. Default 0.5.")
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
                    "información extra, con este mensaje: \"Para generar imágenes, configura la " +
                    "URL del servicio en Configuración → Generación de imágenes.\""
            )
        }

        val sourceDataUrl = sourceImageProvider()
            ?: return errorPayload(
                "NO_SOURCE_IMAGE. No hay ninguna foto disponible para convertir. Pide al usuario " +
                    "que suba una foto, o genera una imagen primero con generate_image."
            )
        val sourceBase64 = sourceDataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (sourceBase64.isBlank()) {
            return errorPayload("La imagen de origen disponible no tiene datos base64 válidos.")
        }

        val request = CartoonRequest(
            image_base64 = sourceBase64,
            prompt = args["prompt"]?.jsonPrimitive?.content,
            denoise = args["denoise"]?.jsonPrimitive?.doubleOrNull
        )

        return api.cartoon(baseUrl, request).fold(
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
                        response.filename?.let { put("filename", it) }
                        response.seed?.let { put("seed", it) }
                        put("note", "Cartoon image generated and shown to the user. Do not echo the base64.")
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
        const val TOOL_NAME = "cartoonify_image"
    }
}
