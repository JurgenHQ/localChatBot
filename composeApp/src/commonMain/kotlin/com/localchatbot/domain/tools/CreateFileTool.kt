package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que crea (o sobrescribe) un archivo de texto en el workspace.
 *
 * Patrón estándar:
 * 1. Parse del JSON.
 * 2. Resolución y validación del path (sandbox).
 * 3. Solicitud de aprobación al usuario (skip si YOLO).
 * 4. Llamada al [FilesystemAgent].
 * 5. Serialización del resultado a JSON para el modelo.
 */
class CreateFileTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Creando archivo…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isWriteAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Creates a UTF-8 text file at the given path. Path can be absolute or " +
                "relative to the configured workspace. Parent directories are created automatically. " +
                "By default fails if the file already exists; pass overwrite=true to replace it. " +
                "Use this for source code, config files, notes — anything text-based. The user is " +
                "asked to approve every call (unless YOLO mode is on).",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path. Relative paths resolve against the workspace.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "UTF-8 text content to write.")
                    })
                    put("overwrite", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, overwrite an existing file. Defaults to false.")
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val path = args["path"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'path' faltante")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'content' faltante")
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull == true

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        val detail = buildString {
            append(abs)
            append("\n\n")
            append(content.take(800))
            if (content.length > 800) append("\n…")
            if (overwrite) append("\n\n⚠ overwrite=true — se reemplaza el archivo existente")
        }

        val approved = confirm.requestApproval(
            title = "Crear archivo",
            detail = detail
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.createFile(abs, content, overwrite))
    }

    companion object {
        const val TOOL_NAME = "create_file"
    }
}
