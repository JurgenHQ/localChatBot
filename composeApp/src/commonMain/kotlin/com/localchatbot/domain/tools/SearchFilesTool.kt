package com.localchatbot.domain.tools

import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool de búsqueda de texto en el workspace (grep nativo, recursivo).
 * Read-only: disponible también en Plan mode y sin confirmación, igual que
 * `read_file` / `list_directory`.
 */
class SearchFilesTool(
    private val agent: FilesystemAgent,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    // Buscar es inofensivo (solo lectura); el sandbox de paths se aplica igual
    // en execute(). Sin confirmación para no romper la autonomía del agente.
    override val requiresConfirmation: Boolean = false

    override val activityLabel: String = "Buscando en archivos…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["pattern"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Searches file contents recursively under the workspace (or a subdirectory). " +
                "`pattern` is a regular expression by default; if it fails to compile the search " +
                "silently falls back to a literal match (`mode` in the response tells you which ran). " +
                "Case-insensitive unless `case_sensitive` is true. Returns matches as `path:line: text` " +
                "with paths relative to the workspace, so you can jump to them with `read_file` using " +
                "`offset`. Skips binary files, files over 1MB and heavy directories (.git, build, " +
                "node_modules, …). Prefer this over `run_command` with grep/find.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Regex (or literal text with literal=true) to search for.")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Directory to search in. Defaults to the workspace root.")
                    })
                    put("literal", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Treat `pattern` as literal text instead of regex. Default false.")
                    })
                    put("case_sensitive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Match case exactly. Default false.")
                    })
                    put("file_glob", buildJsonObject {
                        put("type", "string")
                        put("description", "Only search files whose name matches this glob, e.g. `*.kt`.")
                    })
                    put("max_results", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum matches to return (default 100, max 500).")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val pattern = args["pattern"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return FsToolUtil.errorPayload(json, "Argumento 'pattern' faltante")

        val workspace = preferences.current().fsWorkspaceDir
        val path = args["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: workspace
            ?: return FsToolUtil.errorPayload(json, "Sin workspace configurado y sin 'path'")

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        return FsToolUtil.fsResultToJson(
            json,
            agent.searchFiles(
                absPath = abs,
                pattern = pattern,
                literal = args["literal"]?.jsonPrimitive?.booleanOrNull ?: false,
                caseSensitive = args["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
                fileGlob = args["file_glob"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                maxResults = args["max_results"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 500) ?: DEFAULT_MAX_RESULTS,
                workspaceRoot = workspace
            )
        )
    }

    companion object {
        const val TOOL_NAME = "search_files"
        private const val DEFAULT_MAX_RESULTS = 100
    }
}
