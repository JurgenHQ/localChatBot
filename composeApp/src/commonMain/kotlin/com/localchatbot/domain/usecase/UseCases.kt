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

        // Las tools se envían siempre que el registry no esté vacío. Si una tool no
        // está disponible (p. ej. sin API key) y el modelo la invoca, su execute()
        // devolverá un error explicativo que el modelo relatará al usuario.
        val tools = if (!toolRegistry.isEmpty()) toolRegistry.allDefinitions() else null

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
            "You have access to function tools. Use them when relevant:\n\n" +
                "• `search_web`: fetches current information from the internet. When the user " +
                "asks about news, recent events, current facts, prices, weather, sports results, " +
                "specific people/companies/products, software versions, or any topic that may " +
                "have changed since your training, you MUST call `search_web` — DO NOT reply " +
                "that you cannot browse the internet.\n\n" +
                "• `render_diagram`: renders Mermaid code to a clean PNG. Use this for concept " +
                "maps, mind maps, flowcharts, sequence/class/state/ER diagrams, gantt charts, " +
                "or ANY structured diagram with text labels. NEVER use `generate_image` for " +
                "these — diffusion models produce illegible text. Build complete valid Mermaid " +
                "syntax starting with the diagram type keyword (`graph`, `flowchart`, `mindmap`, " +
                "`sequenceDiagram`, etc.).\n\n" +
                "CRITICAL rules for `render_diagram` (and `generate_image`):\n" +
                "1. NEVER claim you generated a diagram or image without actually calling the " +
                "tool first. If the user asks for one, you MUST call the tool — text-only " +
                "replies like \"here's your diagram\" without a tool call are a hallucination " +
                "and forbidden.\n" +
                "2. Check the tool result carefully. If `success` is true, briefly confirm to " +
                "the user that the diagram/image is ready. If `success` is false, you MUST " +
                "tell the user the diagram could NOT be generated and quote the `error` field " +
                "in the user's language. Never pretend a failed render succeeded.\n" +
                "3. If the diagram failed because of invalid Mermaid syntax, try again ONCE " +
                "with simpler/cleaner code (avoid parentheses inside labels, special chars, " +
                "very long labels). If it still fails, report the failure honestly.\n\n" +
                "• `generate_image`: creates a creative/artistic image from a text description. " +
                "Use this ONLY for natural, artistic, or photorealistic images (a dragon, a " +
                "landscape, a portrait). Translate the description into a detailed English " +
                "prompt for SDXL. After it succeeds, briefly tell the user the image is ready, " +
                "in their language. Do NOT include base64 data in your reply.\n\n" +
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
