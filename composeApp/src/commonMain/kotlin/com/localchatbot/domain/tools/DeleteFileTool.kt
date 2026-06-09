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
 * Tool que elimina un archivo o directorio del workspace. Siempre pide
 * confirmación al usuario (incluso el detail resalta el borrado recursivo)
 * porque la operación es irreversible.
 */
class DeleteFileTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Eliminando…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Deletes a file or directory. Directories are only deleted when empty " +
                "unless recursive=true (which removes all contents). This is irreversible — the " +
                "user is asked to approve every call.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "File or directory to delete. Relative paths resolve against the workspace.")
                    })
                    put("recursive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, delete a non-empty directory with all its contents. Defaults to false.")
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
        val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull == true

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        val detail = buildString {
            append(abs)
            if (recursive) append("\n\n⚠ recursive=true — se borra el directorio CON TODO su contenido")
            append("\n\nEsta acción es irreversible.")
        }

        val approved = confirm.requestApproval(
            title = "Eliminar archivo/directorio",
            detail = detail
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.deletePath(abs, recursive))
    }

    companion object {
        const val TOOL_NAME = "delete_file"
    }
}
