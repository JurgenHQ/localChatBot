package com.localchatbot.domain.tools

import com.localchatbot.core.web.HtmlToText
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.data.remote.WebFetchApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Descarga una URL y devuelve su texto legible.
 *
 * Cierra el hueco entre "puede buscar" y "puede leer": `search_web` devuelve fragmentos de
 * Tavily, pero si el usuario pega el enlace a una documentación el modelo no tenía forma de
 * abrirlo. No requiere API key — a diferencia de la búsqueda, esto es un GET normal.
 *
 * El HTML se convierte a texto ([HtmlToText]) antes de devolverlo: mandarle el markup crudo
 * al modelo gastaría la ventana de contexto en `<div>` y CSS.
 */
class FetchUrlTool(
    private val webFetch: WebFetchApi,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Leyendo página web…"

    override fun activityDetail(argumentsJson: String): String? = extractUrl(argumentsJson)

    override suspend fun isAvailable(): Boolean = true

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Fetch a web page or plain-text/JSON resource by URL and return " +
                "its readable text content. Use this whenever the user gives you a link, " +
                "or when a search result looks relevant and you need the full page instead " +
                "of the snippet. Prefer `search_web` when you need to FIND pages; use this " +
                "when you already know the URL. Returns text only — images and scripts are " +
                "stripped.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute URL, including the http:// or https:// scheme.")
                    })
                    put("max_chars", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum characters of page text to return (default $DEFAULT_MAX_CHARS, " +
                                "hard cap $HARD_MAX_CHARS). The text is truncated, never summarized."
                        )
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("url")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val rawUrl = extractUrl(argumentsJson)?.trim()
        if (rawUrl.isNullOrBlank()) return errorPayload("Argumento 'url' faltante")

        // Un modelo local escribe "example.com" con frecuencia; asumir https evita un
        // fallo que para el usuario no tiene ninguna explicación útil.
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        val maxChars = extractMaxChars(argumentsJson)
            ?.coerceIn(500, HARD_MAX_CHARS)
            ?: DEFAULT_MAX_CHARS

        return webFetch.fetch(url).fold(
            onSuccess = { page ->
                val isHtml = page.contentType?.contains("html", ignoreCase = true) == true ||
                    page.body.contains("<html", ignoreCase = true)
                val text = if (isHtml) HtmlToText.extract(page.body) else page.body.trim()
                val title = if (isHtml) HtmlToText.extractTitle(page.body) else null
                val truncated = text.length > maxChars

                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("url", page.url)
                        put("status", page.status)
                        page.contentType?.let { put("content_type", it) }
                        title?.let { put("title", it) }
                        put("text", text.take(maxChars))
                        if (truncated) {
                            put("truncated", true)
                            put("total_chars", text.length)
                            put(
                                "hint",
                                "Se devolvieron los primeros $maxChars caracteres de ${text.length}. " +
                                    "Vuelve a llamar con un max_chars mayor si necesitas más."
                            )
                        }
                    }
                )
            },
            onFailure = { e ->
                errorPayload("No se pudo descargar $url: ${e.message ?: "error desconocido"}")
            }
        )
    }

    private fun extractUrl(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["url"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun extractMaxChars(argumentsJson: String): Int? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["max_chars"]?.jsonPrimitive?.content?.toInt()
    }.getOrNull()

    private fun errorPayload(message: String): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", message) })

    companion object {
        const val TOOL_NAME = "fetch_url"

        /**
         * Por debajo del recorte de `Tool.kt` (8k) para que el texto llegue entero y sea la
         * tool —no el truncado genérico— quien avise al modelo de que hay más página.
         */
        private const val DEFAULT_MAX_CHARS = 6_000
        private const val HARD_MAX_CHARS = 20_000
    }
}
