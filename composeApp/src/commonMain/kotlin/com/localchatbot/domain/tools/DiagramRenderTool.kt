package com.localchatbot.domain.tools

import com.localchatbot.data.remote.DiagramRenderApi
import com.localchatbot.data.remote.DiagramRenderRequest
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool que renderiza diagramas (Mermaid) a PNG vía el Diagram Service local.
 *
 * Pensada para mapas conceptuales, flujos, secuencia, clases, mindmaps, etc., donde
 * los modelos de difusión (SDXL/FLUX) fallan al renderizar texto legible. Aquí el
 * texto sale perfecto porque no se "genera" pixel a pixel — se renderiza con un
 * parser determinista (mermaid-cli).
 *
 * Igual que [ImageGenerationTool], el base64 NO va al contexto del modelo en el
 * tool result: se expone aparte vía [consumeProducedImage] para que el use case lo
 * adjunte al `ChatMessage` final del assistant.
 */
class DiagramRenderTool(
    private val api: DiagramRenderApi,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    override val activityLabel: String = "Renderizando diagrama…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        // Mostramos la primera línea no vacía del código como detalle (típicamente
        // "graph TD", "mindmap", etc. — suficiente para que el usuario sepa qué se renderiza).
        json.parseToJsonElement(argumentsJson).jsonObject["code"]?.jsonPrimitive?.content
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    }.getOrNull()

    override suspend fun isAvailable(): Boolean =
        preferences.current().effectiveImageServiceUrl.isNotBlank()

    private val _lastImage = MutableStateFlow<String?>(null)

    private fun consumeLastImage(): String? {
        val v = _lastImage.value
        _lastImage.value = null
        return v
    }

    override fun consumeProducedImage(): String? = consumeLastImage()

    override fun peekProducedImage(): String? = _lastImage.value

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Renders a Mermaid diagram to a PNG image. Use this whenever the user " +
                "asks for a concept map, mind map, flowchart, sequence/class/state/ER diagram, " +
                "gantt chart, or any structured diagram. DO NOT use `generate_image` for these — " +
                "diffusion models produce illegible text. Build complete, valid Mermaid syntax " +
                "(starting with the diagram type keyword: `flowchart`, `mindmap`, " +
                "`sequenceDiagram`, `classDiagram`, `stateDiagram`, `erDiagram`, `gantt`, `pie`). " +
                "ESCAPING RULES — follow strictly to avoid parse errors: " +
                "(1) Any node label containing parentheses (), brackets [], curly braces {}, " +
                "colons :, quotes, or special characters MUST be wrapped in double quotes: " +
                "A[\"label with (parens) or [brackets]\"] — never leave special chars unquoted in labels. " +
                "(2) Prefer `flowchart` over `graph` — more robust parser. " +
                "(3) Do NOT use parallelogram shapes [/text/] or [\\text\\] — parser may reject them. " +
                "(4) In `sequenceDiagram`, quote participant names that contain spaces or special chars. " +
                "(5) In `classDiagram`, escape method signatures: use +methodName(param) without extra parens in labels. " +
                "(6) In `mindmap`, avoid parentheses inside node text — use plain text or double-quote the node. " +
                "(7) Generate the richest, most complete diagram the syntax allows — do NOT simplify just to be safe. " +
                "After it succeeds, briefly confirm to the user in their language that the diagram " +
                "is ready. Do NOT include the Mermaid code or base64 in your reply.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "Complete valid Mermaid code, starting with the diagram type keyword.")
                    })
                    put("theme", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional theme: \"default\", \"dark\", \"forest\", or \"neutral\".")
                    })
                    put("background", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional background color (e.g. \"transparent\", \"#ffffff\").")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("code")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return errorPayload("Arguments JSON inválido: ${it.message}") }

        val code = args["code"]?.jsonPrimitive?.content
            ?: return errorPayload("Argumento 'code' faltante")

        val baseUrl = preferences.current().effectiveImageServiceUrl
        if (baseUrl.isBlank()) {
            return errorPayload(
                "MISSING_MEDIA_SERVICE. Responde al usuario, en su mismo idioma y sin añadir " +
                    "información extra, con este mensaje: \"Para renderizar diagramas, configura " +
                    "la URL del servicio en Configuración → Generación de imágenes.\""
            )
        }

        val request = DiagramRenderRequest(
            code = code,
            theme = args["theme"]?.jsonPrimitive?.content,
            background = args["background"]?.jsonPrimitive?.content,
            width = 2400,
            scale = 3
        )

        // Mismo flujo que ImageGenerationTool: server responde con la misma forma
        // (success/image_base64/filename/error), aquí solo cambia la URL y los args del request.
        return api.render(baseUrl, request).fold(
            onSuccess = { response ->
                if (!response.success) {
                    return@fold errorPayload(response.error ?: "El servicio devolvió success=false")
                }
                response.image_base64?.let { b64 ->
                    _lastImage.value = "data:image/png;base64,$b64"
                }
                // Solo metadatos al modelo. El base64 queda fuera del contexto.
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("success", true)
                        response.filename?.let { put("filename", it) }
                        put("note", "Diagram rendered and shown to the user. Do not echo the code or base64.")
                    }
                )
            },
            onFailure = { e -> errorPayload(e.message ?: "Error de red contra el Diagram Service") }
        )
    }

    private fun errorPayload(message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", false)
                put("error", message)
            }
        )

    companion object {
        const val TOOL_NAME = "render_diagram"
    }
}
