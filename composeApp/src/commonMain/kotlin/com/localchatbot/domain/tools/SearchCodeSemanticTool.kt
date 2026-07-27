package com.localchatbot.domain.tools

import com.localchatbot.core.index.WorkspaceIndexer
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
 * Búsqueda **semántica** en el workspace: responde "¿dónde se maneja el rate limiting?"
 * sin conocer el nombre que le puso el autor. Complementa a `search_files`, que es grep y
 * solo encuentra lo que ya sabés nombrar.
 *
 * Read-only → sin confirmación y disponible en modo Plan.
 *
 * El índice se construye **bajo demanda** en la primera búsqueda y se refresca de forma
 * incremental (ver [WorkspaceIndexer]); si no hay modelo de embeddings disponible, la tool
 * devuelve un error explicativo que apunta a `search_files` en vez de fallar en seco.
 */
class SearchCodeSemanticTool(
    private val indexer: WorkspaceIndexer,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = false
    override val activityLabel: String = "Búsqueda semántica…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["query"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Searches the workspace by MEANING rather than by exact text, using an " +
                "embeddings index. Use it when you don't know what the code is called: " +
                "\"where is rate limiting handled?\", \"what validates the login form?\". " +
                "Returns the closest chunks as path + line range + a short preview, ranked by " +
                "similarity — read the promising ones with `read_file` using `offset`. " +
                "Use `search_files` instead when you know the literal string or symbol name; it " +
                "is exact and much cheaper. The index is built on first use and refreshed " +
                "incrementally, so the first call on a large workspace can take a while. Pass " +
                "`reindex: true` only if the index looks stale in a way the incremental refresh " +
                "missed.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "What you are looking for, in natural language. Full questions work " +
                                "better than keywords."
                        )
                    })
                    put("max_results", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum matches to return (default 8, max 25).")
                    })
                    put("reindex", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Rebuild the whole index from scratch before searching. Default false.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val query = args["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return FsToolUtil.errorPayload(json, "Argumento 'query' faltante")
        val limit = args["max_results"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 25) ?: DEFAULT_MAX_RESULTS
        val force = args["reindex"]?.jsonPrimitive?.booleanOrNull ?: false

        val workspace = FsToolUtil.effectiveWorkspace(preferences)
            ?: return FsToolUtil.errorPayload(json, "Sin workspace configurado")

        when (val result = indexer.ensureIndex(workspace, force = force)) {
            is WorkspaceIndexer.IndexResult.Failed ->
                return FsToolUtil.errorPayload(json, result.message)
            is WorkspaceIndexer.IndexResult.Ok -> Unit
        }

        val hits = indexer.search(workspace, query, limit).getOrElse { err ->
            return FsToolUtil.errorPayload(
                json,
                "Falló la búsqueda semántica: ${err.message ?: "error desconocido"}. Probá con `search_files`."
            )
        }

        if (hits.isEmpty()) {
            return FsToolUtil.encode(
                json,
                buildJsonObject {
                    put("success", true)
                    put("query", query)
                    put("count", 0)
                    put(
                        "note",
                        "Sin coincidencias por encima del umbral de similitud. Probá reformular la " +
                            "consulta, o `search_files` si sabés algún nombre literal."
                    )
                }
            )
        }

        return FsToolUtil.encode(
            json,
            buildJsonObject {
                put("success", true)
                put("query", query)
                put("count", hits.size)
                put("results", buildJsonArray {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("path", hit.path)
                            put("start_line", hit.startLine)
                            put("end_line", hit.endLine)
                            // Redondeado: dos decimales alcanzan para comparar candidatos y
                            // evitan meter ruido de punto flotante en el contexto.
                            put("score", (hit.score * 100).toInt() / 100.0)
                            put("preview", hit.preview)
                        })
                    }
                })
            }
        )
    }

    companion object {
        const val TOOL_NAME = "search_code_semantic"
        private const val DEFAULT_MAX_RESULTS = 8
    }
}
