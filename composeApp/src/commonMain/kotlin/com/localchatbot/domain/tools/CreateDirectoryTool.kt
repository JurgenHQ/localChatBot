package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Tool que crea un directorio (incluye padres). */
class CreateDirectoryTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Creando directorio…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Creates a directory at the given path. Path can be absolute or relative " +
                "to the workspace. Intermediate parent directories are created if missing. Idempotent — " +
                "no error if it already exists. The user is asked to approve every call (unless YOLO).",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target directory path.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("path")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val path = args["path"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'path' faltante")

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        val approved = confirm.requestApproval(
            title = "Crear directorio",
            detail = abs
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.createDirectory(abs))
    }

    companion object {
        const val TOOL_NAME = "create_directory"
    }
}
