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
 * Tool que edita un archivo existente reemplazando un fragmento exacto de texto.
 * Mucho más barato en tokens (y menos arriesgado) que reescribir el archivo
 * entero con `create_file`: el modelo solo emite el fragmento viejo y el nuevo.
 */
class EditFileTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Editando archivo…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Edits an existing UTF-8 text file by replacing an exact string. " +
                "`old_string` must match the file content EXACTLY (including whitespace and " +
                "indentation) and must appear exactly once — include surrounding lines to make " +
                "it unique. Pass replace_all=true to replace every occurrence instead. " +
                "ALWAYS prefer this over rewriting a whole file with create_file when changing " +
                "part of an existing file. Read the file first to copy the exact text.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path. Relative paths resolve against the workspace.")
                    })
                    put("old_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact text to replace. Must be unique in the file unless replace_all=true.")
                    })
                    put("new_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Replacement text.")
                    })
                    put("replace_all", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, replace every occurrence of old_string. Defaults to false.")
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("old_string"))
                    add(JsonPrimitive("new_string"))
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
        val oldString = args["old_string"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'old_string' faltante")
        val newString = args["new_string"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'new_string' faltante")
        val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull == true

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        val detail = buildString {
            append(abs)
            append("\n\n− ").append(oldString.take(400))
            if (oldString.length > 400) append("\n…")
            append("\n\n+ ").append(newString.take(400))
            if (newString.length > 400) append("\n…")
            if (replaceAll) append("\n\n⚠ replace_all=true — se reemplazan todas las ocurrencias")
        }

        val approved = confirm.requestApproval(
            title = "Editar archivo",
            detail = detail
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.editFile(abs, oldString, newString, replaceAll))
    }

    companion object {
        const val TOOL_NAME = "edit_file"
    }
}
