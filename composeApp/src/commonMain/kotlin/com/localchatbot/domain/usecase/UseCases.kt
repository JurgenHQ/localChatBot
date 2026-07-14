package com.localchatbot.domain.usecase

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.SafePathResult
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.network.isTransientNetworkError
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.core.util.newId
import com.localchatbot.data.remote.ToolCall
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.MessageAttachment
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.model.WebSource
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.StreamEvent
import com.localchatbot.domain.model.InstalledSkill
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.skill.SkillCatalog
import com.localchatbot.data.mcp.McpToolProvider
import com.localchatbot.domain.tools.ScriptToolFactory
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    private val scope: CoroutineScope,
    private val scriptToolFactory: ScriptToolFactory? = null,
    private val mcpToolProvider: McpToolProvider? = null,
    private val confirm: ToolConfirmationController? = null,
    private val todoTool: com.localchatbot.domain.tools.TodoTool? = null,
    /**
     * Agente de filesystem usado SOLO para construir el bloque de contexto del
     * workspace (cwd, árbol de archivos, git status, AGENTS.md/CLAUDE.md) que se
     * inyecta en el system prompt. Real solo en desktop; en móvil no se usa.
     */
    private val filesystemAgent: FilesystemAgent? = null,
    /**
     * Memoria de preferencias del usuario (`memory.md`). Se lee al inicio del turno
     * para inyectar un resumen en el system prompt; el detalle completo lo consulta el
     * modelo vía `read_memory`. Real solo en desktop.
     */
    private val memoryStore: com.localchatbot.core.storage.MemoryStore? = null,
    /**
     * Última foto adjuntada por el usuario, para que `cartoonify_image`/`animate_image`/
     * `cartoon_video` puedan usarla como imagen de entrada aunque no venga de otra tool.
     */
    private val activeSessionStore: com.localchatbot.core.state.ActiveSessionStore? = null,
    /**
     * Estado foreground/background de la app. En móvil el SO suspende el proceso
     * al pasar a background (iOS además mata el socket del stream); con esto
     * distinguimos ese caso de un fallo real del servidor y reanudamos el
     * stream al volver a foreground en lugar de mostrar un error de red.
     */
    private val appLifecycle: com.localchatbot.core.lifecycle.AppLifecycle? = null,
    /**
     * Checkpoints por turno: snapshotea el estado previo de cada archivo antes de
     * que una tool de mutación lo toque, para poder "revertir este turno" desde
     * la UI. Real solo en desktop.
     */
    private val checkpointStore: com.localchatbot.core.storage.CheckpointStore? = null,
    /**
     * Workspace efectivo de la sesión activa (carpeta del proyecto o el global). Se usa para
     * resolver rutas del checkpoint y construir el bloque `<workspace>`. Si es null se cae al
     * `fsWorkspaceDir` global de preferences (comportamiento previo a proyectos). Real solo en desktop.
     */
    private val activeWorkspaceStore: com.localchatbot.core.state.ActiveWorkspaceStore? = null
) {
    /**
     * Persiste el mensaje del usuario y consume el stream. Si el modelo emite tool_calls,
     * ejecuta cada tool, persiste su resultado como mensaje role=Tool, y vuelve a hacer
     * streaming hasta que el modelo termine sin más tool_calls. Tope MAX_TOOL_ITERATIONS.
     */
    suspend operator fun invoke(
        sessionId: String,
        text: String,
        imageDataUrl: String? = null,
        systemPromptOverride: String? = null,
        attachments: List<MessageAttachment>? = null
    ): Result<Unit> {
        val now = Clock.System.now().toEpochMilliseconds()
        val userMsg = ChatMessage(
            id = newId(),
            role = Role.User,
            content = text,
            timestampEpochMs = now,
            imageDataUrl = imageDataUrl,
            attachments = attachments?.takeIf { it.isNotEmpty() }
        )
        chats.appendMessage(sessionId, userMsg)
        if (imageDataUrl != null) {
            activeSessionStore?.setLastUserImage(imageDataUrl)
        }

        val initialSession = chats.getSession(sessionId)
            ?: return Result.failure(IllegalStateException("session not found"))
        // Placeholder inmediato con los primeros chars; tras la primera respuesta
        // se reemplaza por un título generado por el modelo (ver final del invoke).
        val wasUntitled = initialSession.title == DefaultTitle
        if (wasUntitled) {
            val placeholder = text.ifBlank { attachments?.firstOrNull()?.name.orEmpty() }
            chats.updateTitle(sessionId, placeholder.take(40))
        }

        val currentPrefs = prefs.current()
        val cfg = currentPrefs.connection
        if (!cfg.isValid()) {
            return Result.failure(IllegalStateException("Conexión no configurada"))
        }

        // Parámetros de generación: override sesión ?: global ?: default (AGENT_TEMPERATURE para agente).
        // Se calculan aquí una vez y se usan en cada ronda del loop de streaming.
        val globalParams = currentPrefs.generationParams
        val sessionParams = initialSession.generationParams
        val baseParams = sessionParams ?: globalParams

        // Solo mandamos las tools disponibles en este momento: sin workspace → sin fs tools,
        // sin API key → sin search_web, etc. Así el modelo nunca intenta invocar una tool
        // que no puede ejecutar.
        val scriptTools = scriptToolFactory?.buildEnabledTools() ?: emptyList()
        val mcpTools = mcpToolProvider?.currentTools() ?: emptyList()
        val tools = (toolRegistry.availableDefinitions()
            + scriptTools.map { it.definition }
            + mcpTools.filter { runCatching { it.isAvailable() }.getOrDefault(false) }.map { it.definition })
            .takeIf { it.isNotEmpty() }

        // Contexto del workspace (cwd, árbol de archivos, git status, AGENTS.md/CLAUDE.md).
        // Se calcula UNA vez por turno y se reinyecta en el system prompt de cada ronda,
        // para que el modelo sepa dónde está y qué archivos existen sin tener que
        // explorarlos a ciegas. Solo desktop con workspace configurado.
        val workspaceContext = buildWorkspaceContext()

        // Resumen de la memoria de preferencias del usuario (memory.md). Se inyecta
        // SIEMPRE (es pequeño y de alto valor); el detalle completo lo lee el modelo con
        // `read_memory`. Estable entre turnos → cache-friendly.
        val memoryContext = buildMemoryContext()

        // Resumen rodante del historial descartado (Fase 3). Se lee una vez al inicio
        // del turno; el job de background que lo actualiza tardará un poco más, pero
        // el siguiente turno ya tendrá el valor fresco.
        var contextSummary = initialSession.contextSummary
        // Evita disparar el resumen más de una vez por turno (el loop ejecuta
        // buildMessagesForApi en cada iteración de tool_calls).
        var summarizeFired = false

        return try {
            var iter = 0
            // Instrucción efímera para re-promptear al modelo cuando anunció una
            // acción pero no emitió el tool_call. NO se persiste como mensaje visible:
            // se inyecta solo en la siguiente request vía buildMessagesForApi y se limpia.
            var pendingNudge: String? = null
            // Reintentos para reconciliar todos pendientes al cerrar el turno.
            var todoNudges = 0
            var lastAssistantId: String? = null
            // Checkpoint del turno: true tras marcar el primer mensaje assistant
            // que mutó archivos (evita re-tagear y re-prunear en cada tool call).
            var checkpointTagged = false
            // ID del último mensaje assistant creado en CUALQUIER iteración
            // (incluyendo los "anunciadores" de tool_calls con content vacío).
            // Sirve como fallback para adjuntar imágenes/sources cuando la
            // iteración final no produce contenido.
            var latestAssistantId: String? = null
            var lastToolResultJson: String? = null
            // Métricas de tokens acumuladas a lo largo de todas las rondas.
            var sumInputTokens = 0
            var sumOutputTokens = 0
            var sumGenerationMs = 0L
            var sumReasoningMs = 0L
            var lastContextTokens: Int? = null
            var hasMetrics = false
            var anyEstimated = false

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

                val (messagesForApi, discarded) = buildMessagesForApi(
                    currentMessages, tools != null, systemPromptOverride, pendingNudge,
                    workspaceContext, contextSummary, memoryContext
                )
                pendingNudge = null

                // Resumen rodante: solo en la primera iteración del turno para no
                // lanzar una llamada al modelo por cada ronda de tool_calls.
                if (!summarizeFired && discarded.isNotEmpty()) {
                    summarizeFired = true
                    val prevSummary = contextSummary
                    val transcript = buildSummaryTranscript(prevSummary, discarded)
                    scope.launch {
                        model.summarize(cfg.baseUrl(), cfg.model, transcript)
                            ?.let { newSummary ->
                                chats.updateContextSummary(sessionId, newSummary)
                                contextSummary = newSummary
                            }
                    }
                }

                // Reintento del round de streaming ante una desconexión transitoria
                // (p.ej. "connection reset by peer" cuando el servidor local cae a mitad
                // de la generación). Sin esto un corte momentáneo aborta toda la tarea.
                // Si ya se creó un assistant message con contenido parcial, lo borramos
                // (deleteMessagesFrom) para que el reintento arranque limpio y no duplique
                // texto. Las métricas no se duplican: solo se acumulan en el evento Finish,
                // que nunca llega en un stream cortado.
                val latestBeforeStream = latestAssistantId
                var streamAttempt = 0
                var backgroundResumes = 0
                // Texto parcial que se venía generando cuando un paso por background
                // cortó el stream. En vez de re-preguntar todo desde cero (que se ve
                // como "regenerar" al volver), se reinyecta como último mensaje
                // assistant SIN cerrar: llama.cpp/LM Studio/Ollama lo tratan como un
                // prefijo y continúan escribiendo desde ahí en vez de arrancar una
                // respuesta nueva. No se persiste (es solo para esta llamada); si el
                // backend no soporta el truco, en el peor caso el modelo repite o
                // salta — sigue siendo mejor que perder visualmente el progreso.
                var resumePrefix: String? = null
                while (true) {
                  // Snapshot ANTES del intento: si el contador avanza durante el
                  // intento, el fallo lo causó el paso por background aunque el
                  // error se entregue ya de vuelta en foreground.
                  val bgCountAtStart = appLifecycle?.backgroundCount?.value ?: 0
                  try {
                val effectiveTemp = baseParams.temperature
                    ?: if (tools != null) AGENT_TEMPERATURE else null
                val effectiveParams = baseParams.copy(temperature = effectiveTemp)
                val seedPrefix = resumePrefix
                if (seedPrefix != null) {
                    val id = ensureAssistantMessage()
                    buffer.append(seedPrefix)
                    chats.updateMessageContent(sessionId, id, buffer.toString())
                    resumePrefix = null
                }
                val attemptMessages = if (seedPrefix != null) {
                    messagesForApi + ChatMessage(
                        id = newId(),
                        role = Role.Assistant,
                        content = seedPrefix,
                        timestampEpochMs = Clock.System.now().toEpochMilliseconds()
                    )
                } else {
                    messagesForApi
                }
                model.streamChatWithTools(
                    cfg.baseUrl(),
                    cfg.model,
                    attemptMessages,
                    tools,
                    generationParams = effectiveParams
                )
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
                                // Acumula métricas de tokens a lo largo de las rondas
                                // (con tools, cada ronda reenvía el contexto creciente).
                                event.inputTokens?.let {
                                    sumInputTokens += it
                                    lastContextTokens = it // la última ronda gana → contexto actual
                                    hasMetrics = true
                                }
                                event.outputTokens?.let { sumOutputTokens += it; hasMetrics = true }
                                event.generationMs?.let { sumGenerationMs += it }
                                event.reasoningMs?.let { sumReasoningMs += it }
                                if (event.estimated) anyEstimated = true
                            }
                        }
                    }
                    .collect()
                    break
                  } catch (e: CancellationException) {
                    throw e
                  } catch (e: Throwable) {
                    // ¿La app pasó por background durante este intento? En móvil el
                    // SO suspende el proceso (iOS mata además el socket) — no es un
                    // fallo del servidor, así que no consume presupuesto de retries:
                    // esperamos el foreground y reanudamos con lo generado hasta ahora
                    // como prefijo (resumePrefix), no desde cero.
                    val backgroundInterference = appLifecycle != null &&
                        backgroundResumes < BACKGROUND_RESUME_MAX &&
                        (!appLifecycle.isForeground.value ||
                            appLifecycle.backgroundCount.value > bgCountAtStart)
                    if (!backgroundInterference &&
                        (!isTransientNetworkError(e) || streamAttempt >= STREAM_MAX_RETRIES)
                    ) throw e
                    // Lo generado hasta ahora, para reinyectarlo como prefijo si es un
                    // corte por background (ver declaración de resumePrefix arriba).
                    val partialContent = buffer.toString()
                    // Rollback del mensaje persistido y reset del estado del intento. Se
                    // hace ANTES de esperar el foreground: si el SO mata el proceso
                    // suspendido, la sesión persistida queda limpia. El texto en sí no
                    // se pierde: viaja en resumePrefix para el siguiente intento.
                    assistantId?.let { chats.deleteMessagesFrom(sessionId, it) }
                    assistantId = null
                    latestAssistantId = latestBeforeStream
                    buffer.clear()
                    reasoningBuffer.clear()
                    finishReason = null
                    finalToolCalls = emptyList()
                    if (backgroundInterference) {
                        backgroundResumes++
                        streamAttempt = 0
                        resumePrefix = partialContent.takeIf { it.isNotBlank() }
                        appLifecycle!!.awaitForeground()
                    } else {
                        streamAttempt++
                    }
                    delay(STREAM_RETRY_DELAY_MS)
                  }
                }

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
                        (toolRegistry.find(c.function.name)
                            ?: scriptTools.firstOrNull { it.name == c.function.name }
                            ?: mcpTools.firstOrNull { it.name == c.function.name })
                            ?.requiresConfirmation == true
                    }

                    suspend fun executeCall(call: ToolCall): Pair<ToolCall, String> {
                        val tool = toolRegistry.find(call.function.name)
                            ?: scriptTools.firstOrNull { it.name == call.function.name }
                            ?: mcpTools.firstOrNull { it.name == call.function.name }
                        val label = tool?.activityLabel
                        if (label != null && tool.isAvailable()) {
                            streamingStateStore.markActivity(
                                sessionId,
                                label,
                                tool.activityDetail(call.function.arguments)
                            )
                        }
                        // Checkpoint por turno: snapshotear el estado previo del archivo
                        // ANTES de que la tool lo mute. Un fallo aquí nunca rompe la tool.
                        val ckStore = checkpointStore
                        val fsAgent = filesystemAgent
                        if (tool != null && call.function.name in MUTATING_FS_TOOLS &&
                            ckStore != null && fsAgent != null
                        ) {
                            runCatching {
                                val mutatedPath = json.parseToJsonElement(call.function.arguments)
                                    .jsonObject["path"]?.jsonPrimitive?.content
                                if (mutatedPath != null) {
                                    val current = prefs.current()
                                    val resolved = fsAgent.resolveSafePath(
                                        activeWorkspaceStore?.current() ?: current.fsWorkspaceDir,
                                        mutatedPath,
                                        current.fsAllowOutsideWorkspace
                                    )
                                    if (resolved is SafePathResult.Ok) {
                                        ckStore.snapshotBeforeMutation(
                                            sessionId, userMsg.id, resolved.absPath, call.function.name
                                        )
                                        // Primera mutación del turno: marcar el mensaje
                                        // assistant con el checkpoint (chip de revert) y
                                        // purgar checkpoints viejos. La carrera entre tools
                                        // paralelas es benigna (doble tag/prune inocuo).
                                        if (!checkpointTagged) {
                                            checkpointTagged = true
                                            chats.updateMessageCheckpoint(sessionId, toolCallMsgId, userMsg.id)
                                            ckStore.pruneSession(sessionId)
                                        }
                                    }
                                }
                            }
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
                        val content = enrichFileToolContent(
                            call.function.name,
                            call.function.arguments,
                            resultText
                        )
                        chats.appendMessage(
                            sessionId,
                            ChatMessage(
                                id = newId(),
                                role = Role.Tool,
                                content = content,
                                timestampEpochMs = Clock.System.now().toEpochMilliseconds(),
                                toolCallId = call.id,
                                toolName = call.function.name
                            )
                        )
                    }
                    streamingStateStore.clearActivity(sessionId)

                    // Si alguna tool de esta ronda termina el turno (ask_user), cedemos
                    // el control al usuario: no re-stremeamos. Su respuesta llegará como
                    // el siguiente mensaje. El `role=tool` ya se persistió arriba, así que
                    // el historial queda válido para el protocolo OpenAI.
                    // Excepción: en YOLO la tool ya auto-respondió en su resultado (opción
                    // recomendada o "continúa"), así que NO rompemos y seguimos el loop.
                    val endsTurn = !yolo && finalToolCalls.any { call ->
                        (toolRegistry.find(call.function.name)
                            ?: scriptTools.firstOrNull { it.name == call.function.name }
                            ?: mcpTools.firstOrNull { it.name == call.function.name })
                            ?.endsTurn == true
                    }
                    if (endsTurn) {
                        lastAssistantId = latestAssistantId
                        break
                    }

                    iter++
                    continue
                }

                // El modelo terminó sin pedir más tools. Si genuinamente necesita
                // preguntar algo al usuario, el prompt le instruye a llamar `ask_user`
                // (que termina el turno explícitamente) en vez de narrar la pregunta.

                // Reconciliación: el modelo terminó pero dejó tareas pendientes en
                // esta sesión. Lo reenganchamos una vez (tope MAX_TODO_NUDGES) para
                // que las complete o las limpie en vez de dejarlas colgadas.
                // Only nudge for pending todos when the model produced no content.
                // If the model wrote text (likely asking a question), respect that as a turn end —
                // nudging would override the question and keep the loop running invisibly.
                if (tools != null && todoTool != null && todoNudges < MAX_TODO_NUDGES && buffer.isEmpty()) {
                    val pending = todoTool.itemsFor(sessionId).filter { !it.done }
                    if (pending.isNotEmpty()) {
                        todoNudges++
                        val listed = pending.joinToString("\n") { "- [${it.id}] ${it.text}" }
                        pendingNudge = PENDING_TODOS_NUDGE_PREFIX + listed
                        iter++
                        continue
                    }
                }

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

            // Adjunta las métricas de tokens al mensaje final del assistant.
            if (lastAssistantId != null && hasMetrics) {
                chats.updateMessageMetrics(
                    sessionId,
                    lastAssistantId!!,
                    com.localchatbot.domain.model.TokenMetrics(
                        inputTokens = sumInputTokens.takeIf { it > 0 },
                        outputTokens = sumOutputTokens.takeIf { it > 0 },
                        generationMs = sumGenerationMs.takeIf { it > 0 },
                        estimated = anyEstimated,
                        contextTokens = lastContextTokens,
                        reasoningMs = sumReasoningMs.takeIf { it > 0 }
                    )
                )
            }

            // SIEMPRE drenamos las imágenes out-of-band de las tools, incluso si la última
            // iteración no produjo contenido. Si no, el base64 queda atrapado en el StateFlow
            // y se filtraría a una conversación posterior. Si no hay mensaje final, caemos al
            // último assistant creado (típicamente el "anunciador" de tool_calls), que el
            // MessageBubble ya muestra cuando tiene imageDataUrl aunque content esté vacío.
            val producedImage = (toolRegistry.allTools() + scriptTools + mcpTools)
                .firstNotNullOfOrNull { it.consumeProducedImage() }
            val targetId = lastAssistantId ?: latestAssistantId
            if (producedImage != null && targetId != null) {
                chats.updateMessageImage(sessionId, targetId, producedImage)
            }

            // Mismo drenaje que las imágenes, pero para el video producido por
            // `animate_image`/`cartoon_video`.
            val producedVideo = toolRegistry.allTools()
                .firstNotNullOfOrNull { it.consumeProducedVideo() }
            if (producedVideo != null && targetId != null) {
                chats.updateMessageVideo(sessionId, targetId, producedVideo)
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

    /**
     * Construye la lista de mensajes que se manda al modelo. Devuelve un par:
     * primero la lista final para la API, segundo los mensajes que quedaron FUERA
     * de la ventana (vacío si no hubo truncado). El caller usa los descartados para
     * disparar el resumen rodante en background.
     */
    private suspend fun buildMessagesForApi(
        currentMessages: List<ChatMessage>,
        hasTools: Boolean,
        systemPromptOverride: String? = null,
        ephemeralNudge: String? = null,
        workspaceContext: String? = null,
        contextSummary: String? = null,
        memoryContext: String? = null
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        val cfg = prefs.current()
        val userSystem = cfg.defaultSystemPrompt.trim()
        val yolo = cfg.fsYoloMode
        val model = cfg.connection.model
        val allSkills = SkillCatalog.allFor(cfg.customSkills)
        val skillsIndex = buildSkillsIndex(cfg.installedSkills.filter { it.enabled }, allSkills)
        val agentMode = activeWorkspaceStore?.currentAgentMode() ?: cfg.agentMode
        val toolPrompt = if (hasTools) buildAgentPrompt(yolo, skillsIndex, agentMode) else ""
        val suffix = buildModelSuffix(model)
        // Orden pensado para el KV-cache de llama.cpp/LM Studio: las partes ESTABLES
        // entre turnos (system del usuario, prompt de agente, suffix de modelo) van
        // primero para que su prefill se reuse desde cache. El contexto del workspace
        // (git status, archivos — cambia entre turnos) va al FINAL del bloque system:
        // así, cuando cambia, solo invalida el cache de lo que viene después de él, no
        // del prompt de agente ni de las definiciones de tools.
        // memoryContext va junto al toolPrompt (parte estable, cache-friendly) y ANTES
        // del workspaceContext (volátil). Las preferencias cambian rara vez.
        val combined = listOf(userSystem, systemPromptOverride?.trim(), toolPrompt, suffix, memoryContext?.trim(), workspaceContext?.trim())
            .filterNot { it.isNullOrBlank() }.joinToString("\n\n")

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
        val discarded: List<ChatMessage>
        val windowed: List<ChatMessage>
        if (keptCount < history.size) {
            // No empezar la ventana con resultados de tool huérfanos (su mensaje
            // assistant "anunciador" quedó fuera del corte).
            val rawWindowed = history.takeLast(keptCount).dropWhile { it.role == Role.Tool }
            val windowedIds = rawWindowed.map { it.id }.toSet()
            discarded = history.filter { it.id !in windowedIds }
            windowed = rawWindowed
        } else {
            discarded = emptyList()
            windowed = history
        }
        val truncated = discarded.isNotEmpty()

        // Compactación: si hay resumen previo (generado por el modelo), lo inyectamos
        // como system message — da al modelo contexto real del historial descartado.
        // Si no hay resumen aún, anclamos la tarea original (sin llamada al modelo).
        val truncationNoticeText = if (truncated) {
            if (!contextSummary.isNullOrBlank()) {
                "Resumen del historial anterior:\n$contextSummary"
            } else {
                val firstUserTask = history.firstOrNull { it.role == Role.User }
                    ?.takeIf { task -> windowed.none { it.id == task.id } }
                    ?.content?.trim()?.take(500)
                buildString {
                    append("Nota: El historial anterior fue recortado por límite de contexto.")
                    if (!firstUserTask.isNullOrBlank()) {
                        append(" La petición original del usuario fue: \"")
                        append(firstUserTask)
                        append("\"")
                    }
                }
            }
        } else null

        // Los adjuntos del usuario viven en un campo aparte (no se muestran crudos en
        // la burbuja). Aquí, SOLO para la petición al modelo, se expanden a bloques
        // fenced al inicio del contenido. La copia es efímera: no se persiste.
        val windowedForApi = windowed.map(::expandAttachmentsForApi)

        // Algunos chat templates Jinja (LM Studio/llama.cpp) exigen que el system sea
        // el ÚNICO mensaje de ese rol y esté en la posición 0. Por eso truncationNotice
        // y la instrucción efímera (nudge) se pliegan dentro del mismo system message
        // en vez de ir como mensajes `role=system` separados — el nudge iba al FINAL de
        // la lista y rompía ese contrato. Se agrega último dentro del bloque para
        // conservar su efecto de "última instrucción leída" antes de generar.
        val finalSystemContent = listOfNotNull(
            combined.takeIf { it.isNotBlank() },
            truncationNoticeText,
            ephemeralNudge?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")

        val apiMessages = if (finalSystemContent.isBlank()) {
            windowedForApi
        } else {
            val systemMsg = ChatMessage(
                id = SYSTEM_PROMPT_ID,
                role = Role.System,
                content = finalSystemContent,
                timestampEpochMs = 0L
            )
            listOf(systemMsg) + windowedForApi
        }
        return apiMessages to discarded
    }

    /**
     * Expande los adjuntos de un mensaje del usuario a bloques fenced antepuestos al
     * contenido. Devuelve una copia efímera para la API; si no hay adjuntos, el mismo
     * mensaje. No muta ni persiste nada.
     */
    private fun expandAttachmentsForApi(msg: ChatMessage): ChatMessage {
        val atts = msg.attachments
        if (atts.isNullOrEmpty()) return msg
        val expanded = buildString {
            atts.forEach { f ->
                appendLine("```${f.name}")
                appendLine(f.content)
                appendLine("```")
                appendLine()
            }
            append(msg.content)
        }.trim()
        return msg.copy(content = expanded)
    }

    /** Construye un transcript legible para el modelo a partir de los mensajes descartados. */
    private fun buildSummaryTranscript(previousSummary: String?, discarded: List<ChatMessage>): String =
        buildString {
            if (!previousSummary.isNullOrBlank()) {
                append("Resumen del contexto anterior:\n")
                append(previousSummary)
                append("\n\n")
            }
            append("Historial a resumir:\n")
            for (msg in discarded) {
                when (msg.role) {
                    Role.User -> append("Usuario: ${msg.content.take(600)}\n")
                    Role.Assistant -> if (msg.content.isNotBlank()) append("Asistente: ${msg.content.take(600)}\n")
                    Role.Tool -> append("[Tool ${msg.toolName ?: "?"}: ${msg.content.take(200)}]\n")
                    Role.System -> {}
                }
            }
        }

    /**
     * Construye el bloque `<workspace>` que se inyecta en el system prompt: cwd,
     * árbol de archivos (un nivel), git status y el contenido de los archivos de
     * reglas del proyecto (AGENTS.md / CLAUDE.md / .cursorrules) si existen. Da al
     * modelo orientación inmediata del proyecto sin gastar rondas de tool calls
     * explorando a ciegas. Solo desktop con workspace configurado; devuelve null en
     * cualquier otro caso o si la recolección falla por completo. Cada pieza falla
     * en silencio por separado (un repo sin git no impide el árbol de archivos).
     */
    /**
     * Bloque `<user-memory>` con las preferencias guardadas (memory.md). Inyecta el
     * archivo entero si es pequeño; si supera [MEMORY_INJECT_CAP] mete solo el principio
     * y avisa al modelo de que llame `read_memory` para el resto. Null si no hay memoria
     * o la plataforma no la soporta.
     */
    private fun buildMemoryContext(): String? {
        val store = memoryStore ?: return null
        if (!store.isAvailable) return null
        val raw = store.read()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val truncated = raw.length > MEMORY_INJECT_CAP
        val body = if (truncated) {
            raw.take(MEMORY_INJECT_CAP).substringBeforeLast('\n') +
                "\n…(memoria recortada — usa read_memory para el resto)"
        } else {
            raw
        }
        return "<user-memory>\n$body\n</user-memory>"
    }

    private suspend fun buildWorkspaceContext(): String? {
        val agent = filesystemAgent ?: return null
        if (!PlatformCapabilities.isDesktop) return null
        val ws = (activeWorkspaceStore?.current() ?: prefs.current().fsWorkspaceDir) ?: return null

        // Árbol raíz (no recursivo): barato y suficiente para que el modelo sepa qué
        // hay sin tener que llamar `list_directory` antes de cada tarea.
        val tree = runCatching {
            (agent.listDirectory(ws) as? FsResult.Ok)?.payload
                ?.get("entries")?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                // Directorios primero, luego archivos — alfabético dentro de cada grupo.
                ?.sortedWith(
                    compareBy(
                        { (it["type"]?.jsonPrimitive?.content != "dir") },
                        { it["name"]?.jsonPrimitive?.content ?: "" }
                    )
                )
                ?.take(120)
                ?.joinToString("\n") { e ->
                    val n = e["name"]?.jsonPrimitive?.content ?: ""
                    if (e["type"]?.jsonPrimitive?.content == "dir") "  $n/" else "  $n"
                }
        }.getOrNull()

        // Git status (rama + archivos modificados). Falla en silencio si no es repo.
        val git = runCatching {
            (agent.runCommand(
                command = "git status --porcelain=v1 -b 2>/dev/null | head -40",
                workingDir = ws,
                timeoutSeconds = 5
            ) as? FsResult.Ok)
                ?.payload?.get("stdout")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val sb = StringBuilder()
        sb.append("<workspace>\n")
        sb.append("cwd: ").append(ws).append("\n")
        if (!git.isNullOrBlank()) sb.append("\ngit status:\n").append(git).append("\n")
        if (!tree.isNullOrBlank()) sb.append("\nfiles (root):\n").append(tree).append("\n")
        sb.append("</workspace>")

        // Reglas del proyecto definidas por el usuario. Leídas en crudo (sin números
        // de línea) y recortadas para no inflar el contexto.
        for (name in listOf("AGENTS.md", "CLAUDE.md", ".cursorrules")) {
            val content = runCatching {
                val abs = (agent.resolveSafePath(ws, name, allowOutside = false)
                    as? SafePathResult.Ok)?.absPath ?: return@runCatching null
                (agent.readFileRaw(abs) as? FsResult.Ok)
                    ?.payload?.get("content")?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                sb.append("\n\n<project-rules file=\"").append(name).append("\">\n")
                sb.append(content.take(6000))
                sb.append("\n</project-rules>")
            }
        }

        return sb.toString().takeIf { it.isNotBlank() }
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

    private fun enrichFileToolContent(toolName: String, argsJson: String, resultJson: String): String {
        if (toolName !in FILE_ACTION_TOOLS) return resultJson
        return runCatching {
            val result = json.parseToJsonElement(resultJson).jsonObject
            if (result["error"] != null) return resultJson
            val path = json.parseToJsonElement(argsJson).jsonObject["path"]?.jsonPrimitive?.content
                ?: return resultJson
            buildJsonObject {
                result.forEach { (k, v) -> put(k, v) }
                put("_path", path)
            }.toString()
        }.getOrDefault(resultJson)
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

        val FILE_ACTION_TOOLS = setOf(
            "edit_file", "multi_edit", "create_file", "delete_file", "create_directory", "save_image"
        )

        /**
         * Máximo de rondas (request → tool_calls → execute → request) en una
         * sola invocación al modelo. Cada ronda puede incluir varios tool_calls
         * paralelos. Alto a propósito: las tareas reales del agente (refactors,
         * debugging, exploración de proyecto) necesitan muchas rondas en YOLO
         * mode — límites bajos cortaban conversaciones a medias y dejaban la UI
         * aparentemente colgada.
         */
        const val MAX_TOOL_ITERATIONS = 200

        /**
         * Tools cuyo efecto sobre archivos se snapshotea para el checkpoint del
         * turno. `run_command`, MCP y scripts de skills quedan fuera (no hay
         * forma genérica de saber qué archivos tocan).
         */
        val MUTATING_FS_TOOLS = setOf(
            "create_file", "edit_file", "multi_edit",
            "delete_file", "create_directory", "save_image"
        )

        /**
         * Tope de caracteres de memory.md que se inyectan en el system prompt. Por
         * encima, se inyecta solo el principio y el modelo lee el resto con `read_memory`.
         * ~2000 chars ≈ 500 tokens: suficiente para preferencias normales sin inflar.
         */
        private const val MEMORY_INJECT_CAP = 2000

        /**
         * Temperatura para el loop de agente (cuando hay tools). Baja a propósito:
         * la decisión "¿invoco la tool?", los argumentos JSON y los edits con match
         * exacto son tareas que quieren estabilidad, no creatividad. El chat normal
         * (sin tools) sigue usando el default del servidor (~0.7).
         */
        private const val AGENT_TEMPERATURE = 0.3

        /**
         * Tope de veces que reenganchamos al modelo para reconciliar todos que
         * quedaron pendientes al cerrar el turno. Acota el bucle si el modelo se
         * niega a completar o limpiar.
         */
        private const val MAX_TODO_NUDGES = 2

        private const val PENDING_TODOS_NUDGE_PREFIX =
            "Aún quedan tareas marcadas como pendientes. Si ya están hechas, llama " +
                "`manage_todos` operation=complete con cada id. Si las abandonaste, llama " +
                "operation=clear. Si no, continúa el trabajo. No termines el turno dejando " +
                "tareas pendientes.\nPendientes:\n"

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
                (msg.attachments?.sumOf { estimateTokens(it.name) + estimateTokens(it.content) + 6 } ?: 0) +
                (if (msg.role == Role.User && msg.imageDataUrl != null) USER_IMAGE_TOKEN_ESTIMATE else 0) +
                4

        private const val RETRY_MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L

        /**
         * Reintentos del round de streaming ante una caída transitoria de la conexión
         * (connection reset / timeout) a mitad de la generación. Cada reintento re-envía
         * el MISMO contexto (los resultados de tool ya persistidos), tras borrar el
         * parcial del intento fallido.
         */
        private const val STREAM_MAX_RETRIES = 2
        private const val STREAM_RETRY_DELAY_MS = 800L

        /**
         * Tope de reanudaciones tras pasos por background en un mismo turno.
         * Válvula contra toggles patológicos de foreground/background; al
         * superarlo, el fallo cae en el presupuesto normal de retries.
         */
        private const val BACKGROUND_RESUME_MAX = 10

        private const val SYSTEM_PROMPT_ID = "system-tools-prompt"

        fun buildSkillsIndex(enabledSkills: List<InstalledSkill>, allSkills: List<SkillDefinition>): String {
            if (enabledSkills.isEmpty()) return ""
            val lines = enabledSkills.mapNotNull { installed ->
                allSkills.firstOrNull { it.id == installed.skillId }?.let { "• ${it.id}: ${it.description}" }
            }
            if (lines.isEmpty()) return ""
            return "Available skills (call `use_skill` with the skill_id to load full instructions):\n" +
                lines.joinToString("\n")
        }

        fun buildAgentPrompt(
            yoloMode: Boolean,
            skillsIndex: String = "",
            agentMode: com.localchatbot.domain.model.AgentMode = com.localchatbot.domain.model.AgentMode.Build
        ): String {
            // En móvil no existen las tools de filesystem/shell/run_command, ni el bloque
            // workspace, ni memoria/checkpoints/plan-mode. Enviar toda esa guía desperdicia
            // tokens; devolvemos un prompt reducido con solo lo que sí aplica en móvil.
            if (!PlatformCapabilities.isDesktop) return buildMobileAgentPrompt(skillsIndex)

            val planBlock = if (agentMode == com.localchatbot.domain.model.AgentMode.Plan) {
                "=== PLAN MODE (read-only) ===\n" +
                    "You are in PLAN mode: investigate and produce a concrete plan, but DO NOT " +
                    "modify anything. The file-mutating tools (create_file, edit_file, multi_edit, " +
                    "delete_file, create_directory, save_image) are DISABLED and not available to " +
                    "you. Use read_file, list_directory and search_files to understand the task. " +
                    "`run_command` is available but you MUST restrict it to read-only inspection " +
                    "(grep, find, cat, git log/status/diff) — never run commands that write, " +
                    "install, delete, move, or commit. End by presenting the plan and telling the " +
                    "user to switch to Build mode to apply it.\n" +
                    "=============================\n\n"
            } else ""
            val permissionLine = if (yoloMode) {
                "Tools run immediately — call them directly, no permission needed. Narrate each " +
                    "action as a statement (\"Leyendo X…\", \"Ejecutando Y…\")."
            } else {
                "The app shows a confirmation dialog for fs/shell tools automatically, so just " +
                    "call the tool — don't ask for permission in text."
            }
            return planBlock + "You are a capable coding agent with function tools. Prefer acting with your " +
                "tools over asking the user to do something a tool can do — the tools listed are " +
                "available to you right now.\n\n" +
                "=== CRITICAL RULE: HOW TO ASK THE USER SOMETHING ===\n" +
                "Whenever you need input from the user — a decision, a choice, missing information, " +
                "clarification, confirmation — you MUST call the `ask_user` tool. " +
                "NEVER write a question as plain text: a plain-text question does NOT pause your " +
                "turn. The loop will keep running, the user will not answer, and you'll be stuck. " +
                "Only `ask_user` actually pauses and waits for a reply. " +
                "Use `options` for multiple choices and always set `recommended` to your best default " +
                "so the hands-off (YOLO) mode can auto-select and keep working.\n" +
                "=====================================================\n\n" +
                "If a `<workspace>` block is present in this prompt, it already gives you the cwd, " +
                "the file tree, git status, and any project rules — use it instead of re-listing " +
                "the directory, and follow the project rules it contains.\n\n" +
                "When to reach for each tool:\n" +
                "• Read, review, analyze, explore, or modify a project / files / code → use " +
                "`list_directory` and `read_file` rather than asking the user to paste code.\n" +
                "• Find text, symbols, usages or definitions across the project → `search_files` " +
                "(regex; returns `path:line:` hits you can open with `read_file` + offset). Prefer " +
                "it over `run_command` with grep/find.\n" +
                "• Create a NEW file → `create_file`.\n" +
                "• Change part of an EXISTING file → `read_file` first, then `edit_file` with exact " +
                "old/new strings. A targeted `edit_file` beats rewriting the whole file.\n" +
                "• Change MULTIPLE independent hunks in one file atomically → `multi_edit` (all edits " +
                "validate before any write; edit fails cleanly if any hunk is not found).\n" +
                "• Delete a file or folder → `delete_file`. Create a folder → `create_directory`.\n" +
                "• Run a command, build, test, install deps, start a server, run a script, or any " +
                "git/terminal operation → `run_command`. For servers/watchers/long-running " +
                "processes set `background=true`.\n" +
                "• News, recent events, prices, current facts, software versions, or anything that " +
                "may have changed since training → `search_web`.\n" +
                "• A diagram / flowchart / mind map / sequence/class/ER diagram → `render_diagram` " +
                "with Mermaid syntax (not `generate_image`).\n" +
                "• An artistic / photorealistic image → `generate_image` with a detailed English " +
                "SDXL prompt.\n" +
                "• Persist a generated image to disk (so the user doesn't have to download it " +
                "manually) → `save_image` with a relative path right after generating it. Do this " +
                "whenever the user wants to keep or reuse the image.\n" +
                "• You need a decision from the user, are missing information no tool can get, or want " +
                "to offer choices → `ask_user` (see CRITICAL RULE above).\n\n" +
                "Working agreement:\n" +
                "1. Never fabricate tool results — if you didn't call a tool, don't claim you did. " +
                "When a tool fails, quote its `error` field in the user's language.\n" +
                "2. Paths in fs tools may be relative (resolved against the workspace) or absolute.\n" +
                "3. $permissionLine\n" +
                "4. After a tool result, summarize briefly in the user's language. Don't paste " +
                "base64 image data into your reply.\n" +
                "5. For multi-step work (list → read → analyze → run), chain the steps in one flow " +
                "instead of stopping between them.\n" +
                "6. Narrate before each tool call — a short line saying what you're about to do " +
                "(\"Leyendo el archivo X…\", \"Instalando dependencias…\") so the user can follow " +
                "progress instead of staring at a spinner.\n" +
                "7. For a complex multi-step task, plan it first with `manage_todos` operation=add " +
                "— ideally ONE call passing all steps in `texts` (duplicate texts are ignored; use " +
                "operation=list if unsure what exists). Then complete each step with " +
                "operation=complete the moment it finishes, not deferred to the end. Don't end " +
                "your turn with tasks still pending — complete them, or operation=clear if " +
                "abandoned.\n\n" +
                (if (PlatformCapabilities.isDesktop) {
                    "\n\nIf you're unsure how to use a tool, or a tool keeps failing (e.g. an " +
                        "`edit_file` match fails twice), call `read_tool_docs` to read the tool " +
                        "guide and recover — don't guess or give up." +
                        "\n\nUser memory: a `<user-memory>` block above (if present) holds the user's " +
                        "saved preferences — honor them in every relevant task (commits, naming, " +
                        "tone, language, tooling). Call `read_memory` for the full list before tasks " +
                        "where their conventions matter (e.g. writing a commit). When the user states " +
                        "a lasting preference (\"remember…\", \"always…\", \"I prefer…\"), call " +
                        "`save_memory` to persist it."
                } else "") +
                "\n\nAlways answer in the same language the user used." +
                (if (skillsIndex.isNotBlank()) "\n\n$skillsIndex" else "")
        }

        /**
         * Prompt de agente reducido para móvil: solo cubre las tools que existen en
         * móvil (search_web, generate_image, render_diagram, manage_todos, ask_user y
         * tools MCP HTTP). Omite todo lo de filesystem/shell/run_command, workspace,
         * plan-mode y memoria — inexistente en móvil — para no inflar el system prompt.
         */
        fun buildMobileAgentPrompt(skillsIndex: String = ""): String =
            "You have a few function tools. Prefer using them over asking the user to do " +
                "something a tool can do.\n\n" +
                "=== CRITICAL RULE: HOW TO ASK THE USER SOMETHING ===\n" +
                "Whenever you need input from the user — a decision, a choice, missing " +
                "information, clarification — you MUST call the `ask_user` tool. NEVER write a " +
                "question as plain text: it does NOT pause your turn, so the user won't answer " +
                "and you'll be stuck. Use `options` for choices and set `recommended` to your " +
                "best default.\n" +
                "=====================================================\n\n" +
                "When to reach for each tool:\n" +
                "• News, recent events, prices, current facts, or anything that may have changed " +
                "since training → `search_web`.\n" +
                "• A diagram / flowchart / mind map / sequence/class/ER diagram → `render_diagram` " +
                "with Mermaid syntax (not `generate_image`).\n" +
                "• An artistic / photorealistic image → `generate_image` with a detailed English " +
                "SDXL prompt.\n" +
                "• You need a decision or missing information no tool can get → `ask_user` (see " +
                "CRITICAL RULE above).\n\n" +
                "Working agreement:\n" +
                "1. Never fabricate tool results — if you didn't call a tool, don't claim you did. " +
                "When a tool fails, quote its `error` field in the user's language.\n" +
                "2. After a tool result, summarize briefly in the user's language. Don't paste " +
                "base64 image data into your reply.\n" +
                "3. For a complex multi-step task, plan it first with `manage_todos` operation=add " +
                "(ideally ONE call passing all steps in `texts`), then complete each step with " +
                "operation=complete as it finishes.\n\n" +
                "Always answer in the same language the user used." +
                (if (skillsIndex.isNotBlank()) "\n\n$skillsIndex" else "")
    }
}

class CheckConnectionUseCase(private val model: ModelRepository) {
    suspend operator fun invoke(baseUrl: String): Result<Long> = model.ping(baseUrl)
}

class ListModelsUseCase(private val model: ModelRepository) {
    suspend operator fun invoke(baseUrl: String): Result<List<String>> = model.listModels(baseUrl)
}
