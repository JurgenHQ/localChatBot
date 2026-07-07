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
 * Tool que persiste en disco el **último video generado** out-of-band (por `animate_image` o
 * `cartoon_video`). Mismo patrón que [SaveImageTool]: peek (no consume) vía [lastVideoProvider],
 * decodifica base64 y escribe en el workspace. Solo desktop, requiere confirmación.
 */
class SaveVideoTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json,
    /** Devuelve el data URL del último video generado SIN consumirlo, o null. */
    private val lastVideoProvider: () -> String?
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Guardando video…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isWriteAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Saves the most recently generated video (from animate_image or " +
                "cartoon_video) to a file on disk. Call this right after generating a video when " +
                "the user wants to keep it. The path is relative to the configured workspace " +
                "(e.g. \"videos/clip.mp4\"); a \".mp4\" extension is added if missing. There must " +
                "be a freshly generated video available — if not, generate one first. The user is " +
                "asked to approve the write (unless YOLO mode is on).",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path, relative to the workspace. " +
                            "Use a descriptive name, e.g. \"videos/dragon.mp4\".")
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

        val dataUrl = lastVideoProvider()
            ?: return FsToolUtil.errorPayload(
                json,
                "No hay ningún video generado recientemente para guardar. Genera uno primero " +
                    "con animate_image o cartoon_video, y luego llama a save_video."
            )

        val base64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (base64.isBlank()) {
            return FsToolUtil.errorPayload(json, "El video disponible no tiene datos base64 válidos.")
        }
        val bytes = runCatching { Base64.decode(base64) }
            .getOrElse { return FsToolUtil.errorPayload(json, "No se pudo decodificar el video: ${it.message}") }

        val path = ensureMp4Extension(rawPath)

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        val detail = buildString {
            append(abs)
            append("\n\n")
            append("Video MP4 · ${bytes.size} bytes")
            if (overwrite) append("\n\n⚠ overwrite=true — se reemplaza el archivo existente")
        }

        val approved = confirm.requestApproval(
            title = "Guardar video",
            detail = detail
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.writeBytes(abs, bytes, overwrite))
    }

    private fun ensureMp4Extension(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val hasVideoExt = VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        return if (hasVideoExt) path else "$path.mp4"
    }

    companion object {
        const val TOOL_NAME = "save_video"
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".mov")
    }
}
