package com.localchatbot.domain.usecase

import com.localchatbot.core.state.StreamingStateStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

private fun newId(): String =
    Clock.System.now().toEpochMilliseconds().toString(36) +
        "-" + Random.nextInt(0, 1_000_000).toString(36)

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
    private val json: Json
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
        if (initialSession.title == DefaultTitle) {
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
                var finishReason: String? = null
                var finalToolCalls: List<ToolCall> = emptyList()

                val messagesForApi = buildMessagesForApi(currentMessages, tools != null)
                model.streamChatWithTools(cfg.baseUrl(), cfg.model, messagesForApi, tools)
                    .onEach { event ->
                        when (event) {
                            is StreamEvent.ContentDelta -> {
                                buffer.append(event.text)
                                val current = buffer.toString()
                                val id = assistantId
                                if (id == null) {
                                    val newAssistantId = newId()
                                    assistantId = newAssistantId
                                    latestAssistantId = newAssistantId
                                    chats.appendMessage(
                                        sessionId,
                                        ChatMessage(
                                            id = newAssistantId,
                                            role = Role.Assistant,
                                            content = current,
                                            timestampEpochMs = Clock.System.now().toEpochMilliseconds()
                                        )
                                    )
                                } else {
                                    chats.updateMessageContent(sessionId, id, current)
                                }
                            }
                            is StreamEvent.Finish -> {
                                finishReason = event.reason
                                finalToolCalls = event.toolCalls
                            }
                        }
                    }
                    .collect()

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
                    finalToolCalls.forEach { call ->
                        val tool = toolRegistry.find(call.function.name)
                        val label = tool?.activityLabel
                        if (label != null && tool.isAvailable()) {
                            streamingStateStore.markActivity(
                                sessionId,
                                label,
                                tool.activityDetail(call.function.arguments)
                            )
                        }
                        val resultText = if (tool == null) {
                            """{"error":"Tool desconocida: ${call.function.name}"}"""
                        } else {
                            runCatching { tool.execute(call.function.arguments) }
                                .getOrElse { """{"error":"${it.message ?: "Error ejecutando tool"}"}""" }
                        }
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
                // El modelo entró en loop. Dejarlo visible al usuario.
                lastAssistantId?.let { id ->
                    chats.updateMessageContent(
                        sessionId,
                        id,
                        "Límite de iteraciones alcanzado. Intenta reformular la pregunta."
                    )
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

    private suspend fun buildMessagesForApi(
        currentMessages: List<ChatMessage>,
        hasTools: Boolean
    ): List<ChatMessage> {
        val userSystem = prefs.current().defaultSystemPrompt.trim()
        val toolPrompt = if (hasTools) SYSTEM_PROMPT_WITH_TOOLS else ""
        val combined = listOf(userSystem, toolPrompt).filter { it.isNotBlank() }.joinToString("\n\n")
        if (combined.isBlank()) return currentMessages
        val systemMsg = ChatMessage(
            id = SYSTEM_PROMPT_ID,
            role = Role.System,
            content = combined,
            timestampEpochMs = 0L
        )
        // Si ya hay un mensaje de sistema persistido (no debería pero por si acaso),
        // lo reemplazamos por el nuestro para no duplicar instrucciones.
        return if (currentMessages.firstOrNull()?.role == Role.System) {
            listOf(systemMsg) + currentMessages.drop(1)
        } else {
            listOf(systemMsg) + currentMessages
        }
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
        const val MAX_TOOL_ITERATIONS = 4
        private const val SYSTEM_PROMPT_ID = "system-tools-prompt"
        private const val SYSTEM_PROMPT_WITH_TOOLS = (
            "You are an agent with function tools. You MUST call the appropriate tool whenever " +
                "the user's request matches one of the rules below. NEVER ask the user to do " +
                "something a tool can do for you. NEVER claim you lack access — the tools are " +
                "listed and available right now.\n\n" +
                "MANDATORY tool usage:\n" +
                "• User asks to read, review, analyze, explore, or modify a project / files / code " +
                "→ call `list_directory` and `read_file`. Do NOT ask the user to paste code.\n" +
                "• User asks to create, write, edit, or save a file → call `create_file`.\n" +
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
                "3. fs/shell tools ask the user to approve each call unless YOLO mode is on.\n" +
                "4. After a successful tool result, summarize briefly in the user's language. Do " +
                "NOT paste base64 image data into your reply.\n" +
                "5. If multiple steps are needed (e.g. list → read → analyze → run), chain them " +
                "in one flow without stopping to ask permission between steps.\n\n" +
                "Always answer in the same language the user used."
            )
    }
}

class CheckConnectionUseCase(private val model: ModelRepository) {
    suspend operator fun invoke(baseUrl: String): Result<Long> = model.ping(baseUrl)
}

class ListModelsUseCase(private val model: ModelRepository) {
    suspend operator fun invoke(baseUrl: String): Result<List<String>> = model.listModels(baseUrl)
}
