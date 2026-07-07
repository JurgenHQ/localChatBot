package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que persiste en disco la **última imagen generada** out-of-band (por
 * `generate_image` o `render_diagram`).
 *
 * El blob base64 nunca entra al contexto del modelo: las tools de imagen lo dejan
 * en su propio estado y el use case lo adjunta al chat. Esta tool lo recupera con
 * [lastImageProvider] (un *peek*, no lo consume — la imagen sigue mostrándose en el
 * chat) y lo escribe como PNG en el workspace, para que el usuario no tenga que
 * descargarla a mano.
 *
 * Solo desktop (necesita filesystem). Requiere confirmación como el resto de tools
 * de escritura (salvo YOLO).
 */
class SaveImageTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json,
    /** Devuelve el data URL de la última imagen generada SIN consumirla, o null. */
    private val lastImageProvider: () -> String?
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Guardando imagen…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isWriteAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Saves the most recently generated image (from generate_image or " +
                "render_diagram) to a file on disk. Call this right after generating an image " +
                "when the user wants to keep it, instead of asking them to download it manually. " +
                "The path is relative to the configured workspace (e.g. \"images/cat.png\"); a " +
                "\".png\" extension is added if missing. There must be a freshly generated image " +
                "available — if not, generate one first. The user is asked to approve the write " +
                "(unless YOLO mode is on).",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path, relative to the workspace. " +
                            "Use a descriptive name, e.g. \"images/sunset.png\".")
                    })
                    put("overwrite", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, overwrite an existing file. Defaults to false.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("path")) })
                put("additionalProperties", false)
            }
        )
    )

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val rawPath = args["path"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'path' faltante")
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull == true

        val dataUrl = lastImageProvider()
            ?: return FsToolUtil.errorPayload(
                json,
                "No hay ninguna imagen generada recientemente para guardar. Genera una primero " +
                    "con generate_image o render_diagram, y luego llama a save_image."
            )

        val base64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (base64.isBlank()) {
            return FsToolUtil.errorPayload(json, "La imagen disponible no tiene datos base64 válidos.")
        }
        val bytes = runCatching { Base64.decode(base64) }
            .getOrElse { return FsToolUtil.errorPayload(json, "No se pudo decodificar la imagen: ${it.message}") }

        val path = ensurePngExtension(rawPath)

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        val detail = buildString {
            append(abs)
            append("\n\n")
            append("Imagen PNG · ${bytes.size} bytes")
            if (overwrite) append("\n\n⚠ overwrite=true — se reemplaza el archivo existente")
        }

        val approved = confirm.requestApproval(
            title = "Guardar imagen",
            detail = detail
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.writeBytes(abs, bytes, overwrite))
    }

    private fun ensurePngExtension(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val hasImageExt = IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        return if (hasImageExt) path else "$path.png"
    }

    companion object {
        const val TOOL_NAME = "save_image"
        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")
    }
}
