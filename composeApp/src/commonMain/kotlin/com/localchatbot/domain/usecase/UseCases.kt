package com.localchatbot.domain.usecase

import com.localchatbot.core.network.isTransientNetworkError
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.core.util.newId
import com.localchatbot.data.remote.ToolCall
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.model.WebSource
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.StreamEvent
import com.localchatbot.domain.tools.ToolRegistry
import com.localchatbot.domain.tools.truncateToolOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CreateSessionUseCase(
    private val chats: ChatRepository,
    private val prefs: PreferencesRepository
) {
    suspend operator fun invoke(): ChatSession {
        val model = prefs.current().connection.model
        return chats.createSession(model)
    }
}

class SendMessageUseCase(
    private val chats: ChatRepository,
    private val model: ModelRepository,
    private val prefs: PreferencesRepository,
    private val toolRegistry: ToolRegistry,
    private val streamingStateStore: StreamingStateStore,
    private val json: Json,
    /**
     * Scope de aplicación para trabajo fire-and-forget que no debe retrasar
     * el retorno del use case (generación de título en background).
     */
    private val scope: CoroutineScope
) {
    /**
     * Persiste el mensaje del usuario y consume el stream. Si el modelo emite tool_calls,
     * ejecuta cada tool, persiste su resultado como mensaje role=Tool, y vuelve a hacer
     * streaming hasta que el modelo termine sin más tool_calls. Tope MAX_TOOL_ITERATIONS.
     */
    suspend operator fun invoke(sessionId: String, text: String, imageDataUrl: String? = null): Result<Unit> {
        val now = Clock.System.now().toEpochMilliseconds()
        val userMsg = ChatMessage(
            id = newId(),
            role = Role.User,
            content = text,
            timestampEpochMs = now,
            imageDataUrl = imageDataUrl
        )
        chats.appendMessage(sessionId, userMsg)

        val initialSession = chats.getSession(sessionId)
            ?: return Result.failure(IllegalStateException("session not found"))
        // Placeholder inmediato con los primeros chars; tras la primera respuesta
        // se reemplaza por un título generado por el modelo (ver final del invoke).
        val wasUntitled = initialSession.title == DefaultTitle
        if (wasUntitled) {
            chats.updateTitle(sessionId, text.take(40))
        }

        val cfg = prefs.current().connection
        if (!cfg.isValid()) {
            return Result.failure(IllegalStateException("Conexión no configurada"))
        }

        // Solo mandamos las tools disponibles en este momento: sin workspace → sin fs tools,
        // sin API key → sin search_web, etc. Así el modelo nunca intenta invocar una tool
        // que no puede ejecutar.
        val tools = toolRegistry.availableDefinitions().takeIf { it.isNotEmpty() }

        return try {
            var iter = 0
            var lastAssistantId: String? = null
            // ID del último mensaje assistant creado en CUALQUIER iteración
            // (incluyendo los "anunciadores" de tool_calls con content vacío).
            // Sirve como fallback para adjuntar imágenes/sources cuando la
            // iteración final no produce contenido.
            var latestAssistantId: String? = null
            var lastToolResultJson: String? = null

            while (iter < MAX_TOOL_ITERATIONS) {
                val currentMessages = chats.getSession(sessionId)?.messages
                    ?: return Result.failure(IllegalStateException("session vanished"))

                var assistantId: String? = null
                val buffer = StringBuilder()
                val reasoningBuffer = StringBuilder()
                var finishReason: String? = null
                var finalToolCalls: List<ToolCall> = emptyList()

                // Crea (lazy) el mensaje assistant la primera vez que llega algo
                // streaming — sea content normal o reasoning. Sin esto, modelos que
                // emiten SOLO reasoning_content (Gemma 3/4, QwQ, DeepSeek-R1)
                // nunca creaban un mensaje y la UI se quedaba en blanco.
                suspend fun ensureAssistantMessage(): String {
                    assistantId?.let { return it }
                    val newAssistantId = newId()
                    assistantId = newAssistantId
                    latestAssistantId = newAssistantId
                    chats.appendMessage(
                        sessionId,
                        ChatMessage(
                            id = newAssistantId,
                            role = Role.Assistant,
                            content = "",
                            timestampEpochMs = Clock.System.now().toEpochMilliseconds()
                        )
                    )
                    return newAssistantId
                }

                val messagesForApi = buildMessagesForApi(currentMessages, tools != null)
                model.streamChatWithTools(cfg.baseUrl(), cfg.model, messagesForApi, tools)
                    .onEach { event ->
                        when (event) {
                            is StreamEvent.ContentDelta -> {
                                buffer.append(event.text)
                                val id = ensureAssistantMessage()
                                chats.updateMessageContent(sessionId, id, buffer.toString())
                            }
                            is StreamEvent.ReasoningDelta -> {
                                reasoningBuffer.append(event.text)
                                val id = ensureAssistantMessage()
                                chats.updateMessageReasoning(sessionId, id, reasoningBuffer.toString())
                            }
                            is StreamEvent.Finish -> {
                                finishReason = event.reason
                                finalToolCalls = event.toolCalls
                                event.actualModel?.let { chats.updateModel(sessionId, it) }
                            }
                        }
                    }
                    .collect()

                // Fallback: si el modelo terminó SOLO con reasoning (sin content ni
                // tool_calls), promovemos el reasoning a content para que el usuario
                // vea ALGO en lugar de un mensaje vacío. Modelos como Gemma 4 con
                // contextos pequeños suelen hacer esto al alcanzar `finish_reason: length`.
                if (buffer.isEmpty() && reasoningBuffer.isNotEmpty() &&
                    (finishReason == null || finishReason != "tool_calls")
                ) {
                    val id = ensureAssistantMessage()
                    val fallback = buildString {
                        append("_El modelo terminó sin emitir respuesta final")
                        if (finishReason == "length") append(" (se quedó sin tokens razonando)")
                        append(". Mostrando su razonamiento:_\n\n")
                        append(reasoningBuffer)
                    }
                    chats.updateMessageContent(sessionId, id, fallback)
                }

                if (finishReason == "tool_calls" && finalToolCalls.isNotEmpty()) {
                    // 1) Persistir un mensaje del assistant que "anuncia" los tool_calls
                    //    (puede no haberse creado si el modelo no emitió content).
                    val toolCallMsgId = assistantId ?: run {
                        val newAssistantId = newId()
                        chats.appendMessage(
                            sessionId,
                            ChatMessage(
                                id = newAssistantId,
                                role = Role.Assistant,
                                content = "",
                                timestampEpochMs = Clock.System.now().toEpochMilliseconds()
                            )
                        )
                        newAssistantId
                    }
                    latestAssistantId = toolCallMsgId
                    chats.updateMessageToolCalls(
                        sessionId,
                        toolCallMsgId,
                        finalToolCalls.map { c ->
                            PersistedToolCall(
                                id = c.id,
                                name = c.function.name,
                                argumentsJson = c.function.arguments
                            )
                        }
                    )

                    // 2) Ejecutar cada tool y persistir su resultado como mensaje Tool.
                    // Ruta paralela si YOLO activo o ninguna tool requiere confirmación.
                    // Ruta secuencial si alguna tool necesita confirmación y YOLO está apagado
                    // (ToolConfirmationController tiene un único slot; dos confirmaciones
                    // simultáneas perderían una).
                    val yolo = prefs.current().fsYoloMode
                    val needsSequential = !yolo && finalToolCalls.any { c ->
                        toolRegistry.find(c.function.name)?.requiresConfirmation == true
                    }

                    suspend fun executeCall(call: ToolCall): Pair<ToolCall, String> {
                        val tool = toolRegistry.find(call.function.name)
                        val label = tool?.activityLabel
                        if (label != null && tool.isAvailable()) {
                            streamingStateStore.markActivity(
                                sessionId,
                                label,
                                tool.activityDetail(call.function.arguments)
                            )
                        }
                        val rawResult = if (tool == null) {
                            """{"error":"Tool desconocida: ${call.function.name}"}"""
                        } else {
                            executeWithRetry(tool, call.function.arguments)
                        }
                        return call to truncateToolOutput(rawResult)
                    }

                    val results: List<Pair<ToolCall, String>> = if (needsSequential) {
                        finalToolCalls.map { executeCall(it) }
                    } else {
                        coroutineScope { finalToolCalls.map { async { executeCall(it) } }.map { it.await() } }
                    }

                    results.forEach { (call, resultText) ->
                        lastToolResultJson = resultText
                        chats.appendMessage(
                            sessionId,
                            ChatMessage(
                                id = newId(),
                                role = Role.Tool,
                                content = resultText,
                                timestampEpochMs = Clock.System.now().toEpochMilliseconds(),
                                toolCallId = call.id,
                                toolName = call.function.name
                            )
                        )
                    }
                    streamingStateStore.clearActivity(sessionId)
                    iter++
                    continue
                }

                // El modelo terminó sin pedir más tools.
                lastAssistantId = assistantId
                break
            }

            // Adjunta sources al último mensaje con contenido (sólo el del final tiene sentido).
            if (lastAssistantId != null && lastToolResultJson != null) {
                val sources = extractSources(lastToolResultJson!!)
                if (sources.isNotEmpty()) {
                    chats.updateMessageSources(sessionId, lastAssistantId!!, sources)
                }
            }

            // SIEMPRE drenamos las imágenes out-of-band de las tools, incluso si la última
            // iteración no produjo contenido. Si no, el base64 queda atrapado en el StateFlow
            // y se filtraría a una conversación posterior. Si no hay mensaje final, caemos al
            // último assistant creado (típicamente el "anunciador" de tool_calls), que el
            // MessageBubble ya muestra cuando tiene imageDataUrl aunque content esté vacío.
            val producedImage = toolRegistry.allTools()
                .firstNotNullOfOrNull { it.consumeProducedImage() }
            val targetId = lastAssistantId ?: latestAssistantId
            if (producedImage != null && targetId != null) {
                chats.updateMessageImage(sessionId, targetId, producedImage)
            }

            if (iter >= MAX_TOOL_ITERATIONS) {
                // El modelo agotó las iteraciones. Mostramos el aviso al usuario:
                // priorizamos el último assistant que SÍ tiene contenido, pero si
                // no existe caemos al "anunciador" de tool_calls (latestAssistantId)
                // para que el mensaje no se pierda en silencio dejando la UI como
                // si siguiera trabajando.
                val notifyId = lastAssistantId ?: latestAssistantId
                if (notifyId != null) {
                    chats.updateMessageContent(
                        sessionId,
                        notifyId,
                        "Límite de iteraciones alcanzado ($MAX_TOOL_ITERATIONS rondas de tool calls). " +
                            "Intenta reformular la pregunta o divídela en pasos más pequeños."
                    )
                } else {
                    // Nunca se creó un assistant message — creamos uno nuevo solo
                    // para reportar el límite y que el usuario vea que terminó.
                    chats.appendMessage(
                        sessionId,
                        ChatMessage(
                            id = newId(),
                            role = Role.Assistant,
                            content = "Límite de iteraciones alcanzado ($MAX_TOOL_ITERATIONS rondas de tool calls). " +
                                "Intenta reformular la pregunta o divídela en pasos más pequeños.",
                            timestampEpochMs = Clock.System.now().toEpochMilliseconds()
                        )
                    )
                }
            }

            // Título real generado por el modelo, fire-and-forget: no retrasa el
            // retorno (la UI dejaría el spinner activo) y si falla se queda el
            // placeholder de los primeros 40 chars.
            if (wasUntitled) {
                val assistantText = chats.getSession(sessionId)?.messages
                    ?.lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
                    ?.content
                if (!assistantText.isNullOrBlank()) {
                    scope.launch {
                        model.generateTitle(cfg.baseUrl(), cfg.model, text, assistantText)
                            .getOrNull()
                            ?.let { title -> chats.updateTitle(sessionId, title) }
                    }
                }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            streamingStateStore.clearActivity(sessionId)
            throw e
        } catch (e: Throwable) {
            streamingStateStore.clearActivity(sessionId)
            Result.failure(e)
        }
    }

    /**
     * Context length del modelo activo, cacheado por baseUrl+model para no
     * pegar al servidor en cada send. LM Studio lo expone; para otros
     * servidores cae al default conservador.
     */
    private var cachedContextKey: String? = null
    private var cachedContextLength: Int? = null

    private suspend fun contextLengthTokens(): Int {
        val cfg = prefs.current().connection
        val key = "${cfg.baseUrl()}|${cfg.model}"
        if (cachedContextKey != key) {
            cachedContextLength = model.fetchContextLength(cfg.baseUrl(), cfg.model)
            cachedContextKey = key
        }
        return cachedContextLength ?: DEFAULT_CONTEXT_TOKENS
    }

    private suspend fun buildMessagesForApi(
        currentMessages: List<ChatMessage>,
        hasTools: Boolean
    ): List<ChatMessage> {
        val cfg = prefs.current()
        val userSystem = cfg.defaultSystemPrompt.trim()
        val yolo = cfg.fsYoloMode
        val model = cfg.connection.model
        val toolPrompt = if (hasTools) buildAgentPrompt(yolo) else ""
        val suffix = buildModelSuffix(model)
        val combined = listOf(userSystem, toolPrompt, suffix).filter { it.isNotBlank() }.joinToString("\n\n")

        // Strip leading system message before windowing — it's re-injected below.
        val history = if (currentMessages.firstOrNull()?.role == Role.System) {
            currentMessages.drop(1)
        } else {
            currentMessages
        }

        // Ventana por presupuesto de tokens estimados (~4 chars/token), no por
        // número de mensajes: un mensaje con un archivo de 50 KB pesa lo que
        // pesa, no "1 mensaje". Reservamos una fracción del contexto para la
        // respuesta del modelo y descontamos el system prompt.
        val budget = (contextLengthTokens() * HISTORY_BUDGET_FRACTION).toInt() -
            estimateTokens(combined)
        var used = 0
        var keptCount = 0
        for (msg in history.asReversed()) {
            val t = estimateMessageTokens(msg)
            // El mensaje más reciente entra siempre, aunque reviente el presupuesto —
            // sin él la petición no tiene sentido.
            if (used + t > budget && keptCount > 0) break
            used += t
            keptCount++
        }
        val (windowed, truncated) = if (keptCount < history.size) {
            // No empezar la ventana con resultados de tool huérfanos (su mensaje
            // assistant "anunciador" quedó fuera del corte).
            history.takeLast(keptCount).dropWhile { it.role == Role.Tool } to true
        } else {
            history to false
        }

        val truncationNotice = if (truncated) {
            listOf(
                ChatMessage(
                    id = "context-truncation-notice",
                    role = Role.System,
                    content = "Nota: El historial anterior fue recortado por límite de contexto.",
                    timestampEpochMs = 0L
                )
            )
        } else emptyList()

        if (combined.isBlank()) return truncationNotice + windowed

        val systemMsg = ChatMessage(
            id = SYSTEM_PROMPT_ID,
            role = Role.System,
            content = combined,
            timestampEpochMs = 0L
        )
        return listOf(systemMsg) + truncationNotice + windowed
    }

    private suspend fun executeWithRetry(tool: com.localchatbot.domain.tools.Tool, argumentsJson: String): String {
        var lastError: Throwable? = null
        for (attempt in 1..RETRY_MAX_ATTEMPTS) {
            val result = runCatching { tool.execute(argumentsJson) }
            if (result.isSuccess) return result.getOrThrow()
            val e = result.exceptionOrNull()!!
            if (!isTransientNetworkError(e)) {
                val msg = e.message?.replace("\"", "'") ?: "Error ejecutando tool"
                return """{"error":"$msg","retried":false,"attempts":$attempt}"""
            }
            lastError = e
            if (attempt < RETRY_MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
        }
        val msg = lastError?.message?.replace("\"", "'") ?: "Error ejecutando tool"
        return """{"error":"$msg","retried":true,"attempts":$RETRY_MAX_ATTEMPTS}"""
    }

    private fun buildModelSuffix(model: String): String = when {
        model.contains("gemma", ignoreCase = true) ->
            "When returning JSON, emit it directly with no preamble."
        model.contains("deepseek", ignoreCase = true) ||
            model.contains("qwq", ignoreCase = true) ||
            model.contains("r1", ignoreCase = true) ->
            "Your internal reasoning is private. Do not describe your thought process in the final answer."
        else -> ""
    }

    private fun extractSources(toolResultJson: String): List<WebSource> = runCatching {
        val obj = json.parseToJsonElement(toolResultJson) as? JsonObject ?: return emptyList()
        val results = obj["results"] as? JsonArray ?: return emptyList()
        results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.content ?: url
            val snippet = o["content"]?.jsonPrimitive?.content ?: ""
            WebSource(title = title, url = url, snippet = snippet.take(200))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val DefaultTitle = "Nueva conversación"
        /**
         * Máximo de rondas (request → tool_calls → execute → request) en una
         * sola invocación al modelo. Cada ronda puede incluir varios tool_calls
         * paralelos. Alto a propósito: las tareas reales del agente (refactors,
         * debugging, exploración de proyecto) necesitan muchas rondas en YOLO
         * mode — límites bajos cortaban conversaciones a medias y dejaban la UI
         * aparentemente colgada.
         */
        const val MAX_TOOL_ITERATIONS = 200

        /** Default conservador cuando el servidor no expone el context length. */
        private const val DEFAULT_CONTEXT_TOKENS = 8192

        /** Fracción del contexto disponible para historial; el resto queda para la respuesta. */
        private const val HISTORY_BUDGET_FRACTION = 0.7

        /** Estimación burda pero universal: ~4 caracteres por token. */
        private const val CHARS_PER_TOKEN = 4

        private fun estimateTokens(text: String): Int = text.length / CHARS_PER_TOKEN

        /**
         * Tokens que consume una imagen adjunta del usuario. El base64 NO se
         * tokeniza como texto: el encoder de visión convierte la imagen en un
         * número de tokens aproximadamente fijo, independiente del tamaño del
         * base64. Las imágenes del assistant (generate_image/render_diagram)
         * nunca viajan al modelo (out-of-band) y cuentan 0.
         */
        private const val USER_IMAGE_TOKEN_ESTIMATE = 600

        /**
         * Tokens estimados de un mensaje: contenido + coste fijo si es un
         * adjunto de usuario (viaja multimodal) + overhead de formato
         * (role, separadores).
         */
        private fun estimateMessageTokens(msg: ChatMessage): Int =
            estimateTokens(msg.content) +
                (if (msg.role == Role.User && msg.imageDataUrl != null) USER_IMAGE_TOKEN_ESTIMATE else 0) +
                4

        private const val RETRY_MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val SYSTEM_PROMPT_ID = "system-tools-prompt"

        fun buildAgentPrompt(yoloMode: Boolean): String {
            val rule3 = if (yoloMode) {
                "Execute tools immediately without asking for permission. Narrate actions as " +
                    "statements ('Leyendo X…', 'Ejecutando Y…'). NEVER ask the user for " +
                    "permission before calling a tool."
            } else {
                "For fs/shell tools the app shows a confirmation dialog automatically. Do NOT " +
                    "ask for permission in text — just call the tool directly."
            }
            return "You are an agent with function tools. You MUST call the appropriate tool whenever " +
                "the user's request matches one of the rules below. NEVER ask the user to do " +
                "something a tool can do for you. NEVER claim you lack access — the tools are " +
                "listed and available right now.\n\n" +
                "MANDATORY tool usage:\n" +
                "• User asks to read, review, analyze, explore, or modify a project / files / code " +
                "→ call `list_directory` and `read_file`. Do NOT ask the user to paste code.\n" +
                "• User asks to create, write, or save a NEW file → call `create_file`.\n" +
                "• User asks to modify, fix, or refactor part of an EXISTING file → call " +
                "`read_file` first, then `edit_file` with the exact old/new strings. NEVER " +
                "rewrite a whole existing file with `create_file` when a targeted edit works.\n" +
                "• User asks to delete a file or folder → call `delete_file`.\n" +
                "• User asks to create a folder/directory → call `create_directory`.\n" +
                "• User asks to run a command, build, test, install deps, start a server, run a " +
                "script, git operation, or anything in a terminal → call `run_command`. For " +
                "servers/watchers/long-running processes set `background=true`.\n" +
                "• User asks about news, recent events, prices, current facts, software versions, " +
                "or anything that may have changed since training → call `search_web`.\n" +
                "• User asks for a diagram, flowchart, mind map, sequence/class/ER diagram → call " +
                "`render_diagram` with Mermaid syntax. NEVER use `generate_image` for diagrams.\n" +
                "• User asks for an artistic / photorealistic image → call `generate_image` with " +
                "a detailed English SDXL prompt.\n\n" +
                "RULES:\n" +
                "1. NEVER fabricate tool results. If you didn't call a tool, do NOT pretend you " +
                "did. If a tool fails, quote the `error` field in the user's language.\n" +
                "2. Paths in fs tools can be relative (resolved against the workspace) or absolute.\n" +
                "3. $rule3\n" +
                "4. After a successful tool result, summarize briefly in the user's language. Do " +
                "NOT paste base64 image data into your reply.\n" +
                "5. If multiple steps are needed (e.g. list → read → analyze → run), chain them " +
                "in one flow without stopping to ask permission between steps.\n" +
                "6. ALWAYS narrate what you are doing BEFORE each tool call. Emit a short message " +
                "describing the action you are about to take (e.g. \"Leyendo el archivo X…\", " +
                "\"Instalando dependencias…\", \"Listando directorio…\") so the user can follow " +
                "your progress in real time. This is critical — without it the user only sees a " +
                "loading indicator with no context.\n" +
                "7. When given a complex multi-step task → call `manage_todos` operation=add for " +
                "each step first, then execute them in order, calling `manage_todos` " +
                "operation=complete after each step succeeds.\n\n" +
                "Always answer in the same language the user used."
        }
    }
}

class CheckConnectionUseCase(private val model: ModelRepository) {
    suspend operator fun invoke(baseUrl: String): Result<Long> = model.ping(baseUrl)
}

class ListModelsUseCase(private val model: ModelRepository) {
    suspend operator fun invoke(baseUrl: String): Result<List<String>> = model.listModels(baseUrl)
}
