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

/** Tool que lista las entradas (no recursivo) de un directorio. */
class ListDirectoryTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Listando directorio…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Lists the immediate (non-recursive) entries of a directory. Returns name, " +
                "type (file|dir) and size in bytes for files. Path can be absolute or relative to the " +
                "workspace. For recursive listing, use `run_command` with `find` or `ls -R`.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Directory path.")
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
            title = "Listar directorio",
            detail = abs
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.listDirectory(abs))
    }

    companion object {
        const val TOOL_NAME = "list_directory"
    }
}
