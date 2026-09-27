package com.localchatbot.domain.tools

import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que lee un archivo de texto paginado por líneas. Devuelve cada línea
 * prefijada con su número, de modo que el modelo puede leer archivos grandes en
 * ventanas (offset/limit) en vez de tragar todo el archivo de golpe.
 */
class ReadFileTool(
    private val agent: FilesystemAgent,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    // Leer es inofensivo (no muta nada) y el sandbox de paths se aplica igual en
    // execute(). Pedir confirmación por cada lectura rompía la autonomía del agente:
    // una tarea de varios pasos forzaba decenas de diálogos. Sin confirmación el
    // modelo explora libremente; solo las escrituras/shell siguen gateadas.
    override val requiresConfirmation: Boolean = false

    override val activityLabel: String = "Leyendo archivo…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Reads a UTF-8 text file, paginated by lines. Path can be absolute or " +
                "relative to the workspace. Each returned line is prefixed with its line number " +
                "(e.g. `42: code`). Reads up to `limit` lines (default 2000) starting at line " +
                "`offset` (1-based, default 1). The response includes `totalLines`, `startLine`, " +
                "`endLine` and `truncated=true` when more lines follow the window — call again with " +
                "a higher `offset` to continue. IMPORTANT: the `N: ` line-number prefix is a " +
                "navigation aid only; do NOT include it in `old_string` when calling edit_file.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path.")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based line number to start reading from. Defaults to 1.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of lines to return. Defaults to 2000.")
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
        val offset = args["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: DEFAULT_LINE_LIMIT

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        return FsToolUtil.fsResultToJson(json, agent.readFile(abs, offset, limit))
    }

    companion object {
        const val TOOL_NAME = "read_file"
        private const val DEFAULT_LINE_LIMIT = 2000
    }
}
