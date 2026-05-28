package com.localchatbot.domain.tools

import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.TavilyApi
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que ejecuta búsquedas web vía Tavily.
 *
 * El modelo recibe la definición JSON estándar de function calling. Cuando la invoca,
 * `execute()` parsea el campo `query` del JSON de argumentos, llama a Tavily y
 * devuelve un JSON con `answer` + lista de resultados (title, url, snippet).
 */
class WebSearchTool(
    private val tavily: TavilyApi,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Buscando en internet…"

    override fun activityDetail(argumentsJson: String): String? = extractQuery(argumentsJson)

    override suspend fun isAvailable(): Boolean = preferences.current().tavilyApiKey.isNotBlank()

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Search the web for current information. ALWAYS call this " +
                "function whenever the user asks about: news, recent events, current " +
                "facts, prices, weather, sports results, dates, specific people, " +
                "companies, products, software versions, or any topic that may have " +
                "changed since your training cutoff. Do NOT respond with 'I cannot " +
                "browse the internet' — call this function instead.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "The search query, in natural language, in the same language the user asked.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val query = extractQuery(argumentsJson)
            ?: return errorPayload("Argumento 'query' faltante")

        val apiKey = preferences.current().tavilyApiKey
        if (apiKey.isBlank()) {
            // Instrucción explícita al modelo: queremos que responda exactamente con
            // este mensaje (traducido al idioma del usuario), sin añadir más razonamientos.
            return errorPayload(
                "MISSING_API_KEY. Responde al usuario, en su mismo idioma y sin añadir " +
                    "información extra, con este mensaje: \"Para poder hacer búsquedas " +
                    "en internet, debes añadir tu API key de Tavily en la sección de " +
                    "Configuración → Búsqueda web (la consigues gratis en app.tavily.com).\""
            )
        }

        val result = tavily.search(apiKey, query)
        return result.fold(
            onSuccess = { response ->
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("query", query)
                        response.answer?.let { put("answer", it) }
                        put("results", buildJsonArray {
                            response.results.forEach { r ->
                                add(buildJsonObject {
                                    put("title", r.title)
                                    put("url", r.url)
                                    put("content", r.content)
                                })
                            }
                        })
                    }
                )
            },
            onFailure = { e -> errorPayload(e.message ?: "Error desconocido de Tavily") }
        )
    }

    private fun extractQuery(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["query"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun errorPayload(message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("error", message)
            }
        )

    companion object {
        const val TOOL_NAME = "search_web"
    }
}
