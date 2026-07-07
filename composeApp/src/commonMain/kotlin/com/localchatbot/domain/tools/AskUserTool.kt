package com.localchatbot.domain.tools

import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.PendingUserPrompt
import com.localchatbot.core.state.PendingUserPromptStore
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool para que el modelo pregunte algo al usuario o le ofrezca opciones a elegir.
 *
 * Es una tool *turn-ending* ([endsTurn] = true): publica la pregunta en el
 * [PendingUserPromptStore] (que la UI renderiza como panel con chips sobre el
 * composer) y el loop termina el turno. La respuesta del usuario llega como el
 * siguiente mensaje normal. Reemplaza a la antigua heurística de detectar frases
 * tipo "¿quieres que…?" en el texto, que en modo YOLO se auto-respondía sola.
 */
class AskUserTool(
    private val activeSessionStore: ActiveSessionStore,
    private val promptStore: PendingUserPromptStore,
    private val prefs: PreferencesRepository
) : Tool {

    override val name: String = TOOL_NAME

    override val endsTurn: Boolean = true

    override val definition: ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Ask the user a question and wait for their answer. Use this whenever you " +
                "need a decision, are missing information you can't obtain with another tool, or want " +
                "to offer choices. ALWAYS prefer this over writing a question in prose — writing a " +
                "question as plain text does NOT pause for the user, so they may never answer it. " +
                "Calling this tool ends your turn until the user replies.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("question", buildJsonObject {
                        put("type", "string")
                        put("description", "The question or prompt to show the user, in their language.")
                    })
                    put("options", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional list of selectable choices. The user taps one, " +
                            "or (if allow_free_text) types their own answer.")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("recommended", buildJsonObject {
                        put("type", "string")
                        put("description", "When you pass `options`, the exact text of the one you'd " +
                            "pick by default. In hands-off (YOLO) mode this option is auto-selected so " +
                            "work continues without interrupting the user; if omitted, the first option is used.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("question")) })
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return """{"error":"Invalid JSON arguments"}""" }
        val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (question.isBlank()) return """{"error":"'question' is required"}"""

        val options = obj["options"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        val recommended = obj["recommended"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }

        // En modo YOLO (manos libres) no interrumpimos: respondemos automáticamente
        // para que el modelo siga trabajando. Con opciones, elegimos la recomendada
        // (o la primera); sin opciones, le indicamos que continúe con su criterio.
        if (prefs.current().fsYoloMode) {
            val answer = if (options.isNotEmpty()) {
                recommended?.let { rec -> options.firstOrNull { it.equals(rec, ignoreCase = true) } ?: rec }
                    ?: options.first()
            } else {
                AUTO_CONTINUE_ANSWER
            }
            return """{"status":"auto_answered","answer":"${answer.escapeJson()}"}"""
        }

        val sessionId = activeSessionStore.activeSessionId.value ?: ""
        promptStore.set(
            PendingUserPrompt(
                sessionId = sessionId,
                question = question,
                options = options,
                allowFreeText = true
            )
        )
        return """{"status":"awaiting_user_response"}"""
    }

    private fun String.escapeJson(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    companion object {
        const val TOOL_NAME = "ask_user"

        private const val AUTO_CONTINUE_ANSWER =
            "Modo automático (YOLO) activo: el usuario no responderá ahora. Toma la mejor " +
                "decisión según tu criterio y continúa el trabajo sin esperar confirmación."
    }
}
