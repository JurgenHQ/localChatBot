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

/** Tool que lee un archivo de texto. Trunca a 200 KB para no inflar el contexto. */
class ReadFileTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Leyendo archivo…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Reads a UTF-8 text file. Path can be absolute or relative to the workspace. " +
                "Truncated at 200 KB; the response includes `truncated=true` if the file is larger — " +
                "use `run_command` with `head`/`tail` for slices of bigger files. The user is asked " +
                "to approve every call (unless YOLO) — files may contain sensitive data.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path.")
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
            title = "Leer archivo",
            detail = abs
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(json, agent.readFile(abs))
    }

    companion object {
        const val TOOL_NAME = "read_file"
    }
}
