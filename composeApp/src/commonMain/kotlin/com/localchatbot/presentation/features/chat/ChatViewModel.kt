package com.localchatbot.presentation.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.background.BackgroundExecutor
import com.localchatbot.core.image.ImageSaver
import com.localchatbot.core.network.friendlyStreamErrorMessage
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.PendingUserPrompt
import com.localchatbot.core.state.PendingUserPromptStore
import com.localchatbot.core.state.QueuedMessage
import com.localchatbot.core.state.QueuedMessageStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.core.storage.CheckpointStore
import com.localchatbot.core.platform.SystemNotifier
import com.localchatbot.core.platform.currentGitBranch
import com.localchatbot.core.voice.TextToSpeech
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.export.ChatExport
import com.localchatbot.core.fs.saveTextFile
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.skill.SkillCatalog
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import com.localchatbot.domain.usecase.CompactContextUseCase
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.InitProjectUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ChatUiState(
    val activeSession: ChatSession? = null,
    val modelName: String = "",
    val draft: String = "",
    val sending: Boolean = false,
    val errorMessage: String? = null,
    val attachedImageBytes: ByteArray? = null,
    /** Actividad de tool en curso (búsqueda, generación de imagen…). Null si no hay. */
    val toolActivity: com.localchatbot.core.state.ToolActivity? = null,
    /** Log de tool calls ejecutadas en el turno actual (label + detail). Vacío si ninguna. */
    val toolCallLog: List<com.localchatbot.core.state.ToolActivity> = emptyList(),
    /** Tokens estimados de la conversación (≈ chars/4). */
    val tokensUsed: Int = 0,
    /** Tamaño máximo del contexto asumido para el indicador visual. */
    val tokensMax: Int = 8192,
    val promptTemplates: List<com.localchatbot.domain.model.PromptTemplate> = emptyList(),
    /** Workspace configurado para las tools de filesystem (null = sin configurar). */
    val fsWorkspaceDir: String? = null,
    /** Si true, las tools de fs se ejecutan sin diálogo de confirmación. */
    val fsYoloMode: Boolean = false,
    /** Si true, las tools de fs aceptan paths fuera del workspace. */
    val fsAllowOutsideWorkspace: Boolean = false,
    /** Si true, edit_file y multi_edit muestran un diff antes de escribir. */
    val fsPreviewEdits: Boolean = false,
    /** Si true, el agente está en modo Plan (solo lectura, sin tools de escritura). */
    val planMode: Boolean = false,
    /** Rama git actual del workspace efectivo, o null si no es un repo git (o no hay workspace). */
    val gitBranch: String? = null,
    /** Skill activa vía /skill en el composer. Persiste hasta que el usuario pulsa la X del badge. */
    val pendingSkill: SkillDefinition? = null,
    /** Skills instalados y habilitados disponibles para invocación explícita /skill. */
    val installedEnabledSkills: List<SkillDefinition> = emptyList(),
    /** Archivos de texto adjuntados pendientes de envío. */
    val attachedTextFiles: List<com.localchatbot.core.fs.AttachedTextFile> = emptyList(),
    /**
     * Si el modelo configurado está cargado en memoria. Null si el backend no expone
     * esa información (backend OpenAI plano) — en ese caso no mostramos aviso de descarga.
     */
    val modelLoaded: Boolean? = null,
    /**
     * Mensajes escritos durante el turno en curso, aún sin enviar. Se mandarán fusionados
     * en uno solo al terminar; hasta entonces se pueden quitar uno a uno.
     */
    val queuedMessages: List<QueuedMessage> = emptyList(),
    /**
     * True si esta sesión tiene un corte de compactación manual activo: parte del historial
     * visible ya no se le manda al modelo, lo representa `contextSummary`.
     */
    val contextCompacted: Boolean = false
) {
    val hasAttachment: Boolean get() = attachedImageBytes != null || attachedTextFiles.isNotEmpty()
}

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val preferences: PreferencesRepository,
    private val projectRepository: ProjectRepository,
    private val activeSessionStore: ActiveSessionStore,
    private val streamingStateStore: StreamingStateStore,
    private val pendingUserPromptStore: PendingUserPromptStore,
    private val queuedMessageStore: QueuedMessageStore,
    private val applicationScope: CoroutineScope,
    private val backgroundExecutor: BackgroundExecutor,
    private val createSessionUseCase: CreateSessionUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val modelRepository: ModelRepository,
    private val imageSaver: ImageSaver,
    private val textToSpeech: TextToSpeech,
    private val systemNotifier: SystemNotifier,
    private val checkpointStore: CheckpointStore? = null,
    /** Compactación manual del contexto (`/compact`). Null = la acción no se ofrece. */
    private val compactContextUseCase: CompactContextUseCase? = null,
    /** Generación de AGENTS.md (`/init`). Null = la acción no se ofrece. */
    private val initProjectUseCase: InitProjectUseCase? = null
) : ViewModel() {

    // ID del mensaje que se está leyendo en voz alta (null = ninguno).
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId

    /**
     * Mensaje al que saltar, pedido por la búsqueda global del drawer. Se expone tal cual
     * desde el store porque el scroll solo puede hacerlo la pantalla (es la que tiene el
     * `LazyListState`), y hasta que los mensajes de la sesión elegida no están cargados no
     * hay a qué índice desplazarse.
     */
    val pendingScrollMessageId: StateFlow<String?> = activeSessionStore.pendingScrollMessageId

    fun consumePendingScroll() = activeSessionStore.consumePendingScroll()

    /**
     * Revert pendiente de confirmación: el usuario pulsó el chip "revertir este
     * turno" y el diálogo muestra los archivos que se restaurarían.
     */
    data class PendingRevert(val checkpointId: String, val files: List<String>)

    private val _pendingRevert = MutableStateFlow<PendingRevert?>(null)
    val pendingRevert: StateFlow<PendingRevert?> = _pendingRevert

    /**
     * Estado del diálogo de compactación manual (`/compact`). El resumen se muestra y se
     * puede editar **antes** de aplicarse: mientras esto no es null no se tocó nada.
     */
    data class CompactState(
        val generating: Boolean = false,
        val summary: String = "",
        val boundaryMessageId: String? = null,
        val messageCount: Int = 0,
        val estimatedTokensFreed: Int = 0,
        val error: String? = null
    )

    private val _compactState = MutableStateFlow<CompactState?>(null)
    val compactState: StateFlow<CompactState?> = _compactState

    /**
     * Abre el diálogo y pide el resumen al modelo. No persiste nada: eso lo hace
     * [applyCompact] con el texto que quede en el editor.
     */
    fun requestCompact() {
        val compact = compactContextUseCase ?: return
        val activeId = activeSessionStore.activeSessionId.value ?: return
        if (streamingStateStore.isStreaming(activeId)) {
            _local.update { it.copy(errorMessage = "Esperá a que termine el turno para compactar") }
            return
        }
        _compactState.value = CompactState(generating = true)
        viewModelScope.launch {
            compact.preview(activeId).fold(
                onSuccess = { preview ->
                    _compactState.value = CompactState(
                        generating = false,
                        summary = preview.summary,
                        boundaryMessageId = preview.boundaryMessageId,
                        messageCount = preview.messageCount,
                        estimatedTokensFreed = preview.estimatedTokensFreed
                    )
                },
                onFailure = { err ->
                    _compactState.value = CompactState(
                        generating = false,
                        error = err.message ?: "No se pudo generar el resumen"
                    )
                }
            )
        }
    }

    fun onCompactSummaryChange(value: String) =
        _compactState.update { it?.copy(summary = value) }

    fun dismissCompact() {
        _compactState.value = null
    }

    /** Aplica el resumen (posiblemente editado). Los mensajes NO se borran. */
    fun applyCompact() {
        val compact = compactContextUseCase ?: return
        val state = _compactState.value ?: return
        val boundary = state.boundaryMessageId ?: return
        val activeId = activeSessionStore.activeSessionId.value ?: return
        val summary = state.summary.trim()
        if (summary.isEmpty()) return
        _compactState.value = null
        viewModelScope.launch {
            compact.apply(activeId, summary, boundary).fold(
                onSuccess = {
                    _local.update {
                        it.copy(
                            errorMessage = "Contexto compactado: ${state.messageCount} mensajes " +
                                "resumidos (siguen visibles en el chat)"
                        )
                    }
                },
                onFailure = { err ->
                    _local.update { it.copy(errorMessage = "No se pudo compactar: ${err.message}") }
                }
            )
        }
    }

    /**
     * Estado del diálogo de `/init`. El contenido propuesto se muestra y se puede editar
     * **antes** de escribirse: mientras esto no es null, `AGENTS.md` no fue tocado.
     */
    data class InitProjectState(
        val generating: Boolean = false,
        val content: String = "",
        val error: String? = null
    )

    private val _initProjectState = MutableStateFlow<InitProjectState?>(null)
    val initProjectState: StateFlow<InitProjectState?> = _initProjectState

    /** Abre el diálogo y pide el borrador de AGENTS.md al modelo. No escribe nada. */
    fun requestInitProject() {
        val init = initProjectUseCase ?: return
        _initProjectState.value = InitProjectState(generating = true)
        viewModelScope.launch {
            init.preview().fold(
                onSuccess = { content ->
                    _initProjectState.value = InitProjectState(generating = false, content = content)
                },
                onFailure = { err ->
                    _initProjectState.value = InitProjectState(
                        generating = false,
                        error = err.message ?: "No se pudo generar AGENTS.md"
                    )
                }
            )
        }
    }

    fun onInitProjectContentChange(value: String) =
        _initProjectState.update { it?.copy(content = value) }

    fun dismissInitProject() {
        _initProjectState.value = null
    }

    /** Escribe AGENTS.md con el contenido (posiblemente editado) del diálogo. */
    fun applyInitProject() {
        val init = initProjectUseCase ?: return
        val state = _initProjectState.value ?: return
        val content = state.content.trim()
        if (content.isEmpty()) return
        _initProjectState.value = null
        viewModelScope.launch {
            init.apply(content).fold(
                onSuccess = {
                    _local.update { it.copy(errorMessage = "AGENTS.md creado en la raíz del workspace") }
                },
                onFailure = { err ->
                    _local.update { it.copy(errorMessage = "No se pudo crear AGENTS.md: ${err.message}") }
                }
            )
        }
    }

    /** Vuelve a mandar el historial completo al modelo. */
    fun undoCompact() {
        val compact = compactContextUseCase ?: return
        val activeId = activeSessionStore.activeSessionId.value ?: return
        viewModelScope.launch {
            compact.undo(activeId)
            _local.update { it.copy(errorMessage = "Compactación deshecha: se vuelve a enviar todo el historial") }
        }
    }

    /** Pide confirmación para revertir el turno [checkpointId] (muestra el diálogo). */
    fun requestRevert(checkpointId: String) {
        val store = checkpointStore ?: return
        val activeId = activeSessionStore.activeSessionId.value ?: return
        if (streamingStateStore.isStreaming(activeId)) return
        viewModelScope.launch {
            val files = store.checkpointSummary(activeId, checkpointId)
            if (files.isEmpty()) {
                _local.update {
                    it.copy(errorMessage = "El checkpoint de este turno ya no existe (fue purgado)")
                }
            } else {
                _pendingRevert.value = PendingRevert(checkpointId, files)
            }
        }
    }

    fun dismissRevert() {
        _pendingRevert.value = null
    }

    /** Restaura los archivos del turno a su estado previo. Los mensajes se conservan. */
    fun confirmRevert() {
        val store = checkpointStore ?: return
        val pending = _pendingRevert.value ?: return
        val activeId = activeSessionStore.activeSessionId.value ?: return
        _pendingRevert.value = null
        viewModelScope.launch {
            val result = store.revert(activeId, pending.checkpointId)
            val summary = buildString {
                append("Revertidos ${result.restored.size} archivo(s)")
                if (result.errors.isNotEmpty()) {
                    append(". Errores: ")
                    append(result.errors.joinToString("; ").take(300))
                }
            }
            _local.update { it.copy(errorMessage = summary) }
        }
    }

    private var speakJob: Job? = null

    /** Lee el mensaje en voz alta vía TTS. Si ya hay otro leyéndose, lo reemplaza. */
    fun speakMessage(messageId: String, text: String) {
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            _speakingMessageId.value = messageId
            runCatching { textToSpeech.speak(stripMarkdown(text), DEFAULT_LANGUAGE_TAG) }
            _speakingMessageId.value = null
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        textToSpeech.stop()
        _speakingMessageId.value = null
    }

    /** Quita la sintaxis markdown para que el TTS no lea asteriscos, almohadillas, etc. */
    private fun stripMarkdown(s: String): String = s
        .replace(Regex("```[\\s\\S]*?```"), " (bloque de código) ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+)\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
        .trim()

    /**
     * Pregunta pendiente que el modelo lanzó vía `ask_user` para la sesión activa
     * (null si no hay). La UI la renderiza como panel con chips sobre el composer.
     */
    val pendingUserPrompt: StateFlow<PendingUserPrompt?> = combine(
        pendingUserPromptStore.prompts,
        activeSessionStore.activeSessionId
    ) { prompts, activeId -> activeId?.let { prompts[it] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _local = MutableStateFlow(LocalState())

    /** Job del stream actualmente en curso, para poder cancelarlo desde la UI. */
    private var streamJob: Job? = null

    /** Longitud de contexto real del modelo cargado (8192 como fallback). */
    private val _tokensMax = MutableStateFlow(DEFAULT_CONTEXT_LENGTH)

    /** Si el modelo configurado está cargado en memoria (null = backend no lo expone). */
    private val _modelLoaded = MutableStateFlow<Boolean?>(null)

    /** Rama git del workspace efectivo actual (null = no es repo git o no hay workspace). */
    private val _gitBranch = MutableStateFlow<String?>(null)

    /** Caché en memoria por workspace: evita relanzar `git` en cada recomposición del mismo dir. */
    private val gitBranchCache = mutableMapOf<String, String?>()

    /**
     * Mismo cálculo de workspace efectivo que el `combine` de más abajo, aislado en su propio
     * flow para poder reaccionar solo a *cambios* de directorio (`distinctUntilChanged`) sin
     * relanzar `git` en cada emisión de `state` (streaming, tokens, etc. cambian mucho más seguido
     * que el workspace).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val effectiveWorkspaceFlow: Flow<String?> = combine(
        activeSessionStore.activeSessionId,
        preferences.preferences,
        projectRepository.state
    ) { activeId, prefs, projectState ->
        activeId
            ?.let { projectState.assignments[it] }
            ?.let { pid -> projectState.projects.firstOrNull { it.id == pid } }
            ?.workspaceDir ?: prefs.fsWorkspaceDir
    }.distinctUntilChanged()

    /**
     * Sesión activa con sus mensajes. Se resuelve por id con [flatMapLatest] en vez de
     * buscarla dentro de una lista de todas las sesiones: así, mientras el modelo escribe,
     * solo se leen y deserializan los mensajes de **esta** sesión (que es la que está en
     * pantalla) y no el historial completo de todas.
     *
     * Emite el **par (id, sesión)** y no la sesión suelta: si el id se leyera aparte de
     * `activeSessionStore`, al cambiar de conversación habría un intervalo —el que tarda la
     * consulta— con el id nuevo y los mensajes de la anterior, y el chat parpadearía con la
     * conversación equivocada. Emparejados, el cambio es atómico: hasta que la sesión nueva
     * no está cargada se sigue viendo la anterior entera y coherente.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeSessionFlow: Flow<Pair<String?, ChatSession?>> =
        activeSessionStore.activeSessionId.flatMapLatest { id ->
            if (id == null) flowOf(null to null)
            else chatRepository.sessionWithMessages(id).map { session -> id to session }
        }

    val state: StateFlow<ChatUiState> = combine(
        combine(
            activeSessionFlow,
            preferences.preferences,
            projectRepository.state
        ) { (activeId, active), prefs, projectState -> SessionData(active, activeId, prefs, projectState) },
        combine(
            streamingStateStore.streaming,
            streamingStateStore.activity,
            streamingStateStore.toolCallLog
        ) { streaming, activity, logs -> Triple(streaming, activity, logs) },
        _local,
        combine(
            _tokensMax,
            _modelLoaded,
            queuedMessageStore.queued,
            _gitBranch
        ) { tokensMax, modelLoaded, queued, gitBranch -> TokensState(tokensMax, modelLoaded, queued, gitBranch) }
    ) { sessionData, streamTriple, local, tokensState ->
        val (tokensMax, modelLoaded, queuedBySession, gitBranch) = tokensState
        val (active, activeId, prefs, projectState) = sessionData
        val (streamingIds, activityMap, toolCallLogs) = streamTriple
        // Workspace y modo EFECTIVOS de la sesión activa (carpeta del proyecto o global;
        // override de modo por sesión o el global). Así los chips reflejan la sesión actual.
        val effectiveWorkspace = activeId
            ?.let { projectState.assignments[it] }
            ?.let { pid -> projectState.projects.firstOrNull { it.id == pid } }
            ?.workspaceDir ?: prefs.fsWorkspaceDir
        val effectiveAgentMode = activeId?.let { prefs.sessionAgentModes[it] } ?: prefs.agentMode
        // Tokens de contexto, lo más realista posible:
        // - Si hay una respuesta del modelo con `contextTokens` reales (prompt_tokens de
        //   su última llamada), usamos ESE número como base — incluye system prompt,
        //   definiciones de tools, resultados de tools e historial, tal como los contó
        //   el servidor.
        // - Le sumamos un estimado (chars/4) de los mensajes posteriores que aún no se
        //   respondieron (típicamente un mensaje nuevo del usuario).
        // - Si todavía no hay métricas reales, estimamos TODO por longitud (incluyendo
        //   resultados de tools, que sí ocupan contexto). Las imágenes van out-of-band.
        val tokensUsed = computeContextTokens(
            messages = active?.messages.orEmpty(),
            boundary = activeId?.let { prefs.sessionCompactBoundaries[it] },
            contextSummary = active?.contextSummary
        )
        val allSkills = SkillCatalog.allFor(prefs.customSkills)
        val installedEnabled = prefs.installedSkills
            .filter { it.enabled }
            .mapNotNull { installed -> allSkills.firstOrNull { it.id == installed.skillId } }
        ChatUiState(
            activeSession = active,
            modelName = active?.model?.takeIf { it.isNotBlank() } ?: prefs.connection.model,
            draft = local.draft,
            sending = activeId != null && activeId in streamingIds,
            errorMessage = local.errorMessage,
            attachedImageBytes = local.attachedImageBytes,
            toolActivity = activeId?.let(activityMap::get),
            toolCallLog = activeId?.let { toolCallLogs[it] } ?: emptyList(),
            tokensUsed = tokensUsed,
            tokensMax = tokensMax,
            promptTemplates = prefs.promptTemplates,
            fsWorkspaceDir = effectiveWorkspace,
            fsYoloMode = prefs.fsYoloMode,
            fsAllowOutsideWorkspace = prefs.fsAllowOutsideWorkspace,
            fsPreviewEdits = prefs.fsPreviewEdits,
            planMode = effectiveAgentMode == com.localchatbot.domain.model.AgentMode.Plan,
            gitBranch = gitBranch,
            pendingSkill = local.pendingSkill,
            attachedTextFiles = local.attachedTextFiles,
            installedEnabledSkills = installedEnabled,
            modelLoaded = modelLoaded,
            queuedMessages = activeId?.let { queuedBySession[it] }.orEmpty(),
            contextCompacted = activeId != null && prefs.sessionCompactBoundaries.containsKey(activeId)
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /**
     * Estima cuántos tokens ocupa el contexto actual. Usa los `contextTokens` reales
     * del último mensaje del modelo (incluyen system prompt, tools y resultados, tal
     * como los contó el servidor) y le suma un estimado de los mensajes posteriores
     * aún sin responder. Si no hay métricas reales todavía, estima todo por longitud.
     */
    private fun computeContextTokens(
        messages: List<ChatMessage>,
        boundary: com.localchatbot.domain.model.CompactBoundary?,
        contextSummary: String?
    ): Int {
        if (messages.isEmpty()) return 0
        fun estimate(msgs: List<ChatMessage>): Int = (msgs.sumOf { it.content.length } + 3) / 4

        // Solo cuenta una medición del servidor POSTERIOR al corte: las anteriores incluían
        // los mensajes que la compactación ya sacó del request, y usarlas dejaba la barra
        // clavada en el número de antes de compactar.
        val freshIdx = messages.indexOfLast {
            it.role == Role.Assistant && it.metrics?.contextTokens != null &&
                (boundary == null || it.timestampEpochMs >= boundary.appliedAtEpochMs)
        }
        if (freshIdx >= 0) {
            return messages[freshIdx].metrics!!.contextTokens!! + estimate(messages.drop(freshIdx + 1))
        }

        // Recién compactado, todavía sin turno nuevo: se estima lo que SÍ se envía (resumen
        // + mensajes posteriores al corte). Al overhead fijo — system prompt y definiciones
        // de tools, que no salen de los mensajes — se lo deduce de la última medición real:
        // es lo que el servidor contó de más respecto del historial que había entonces.
        val boundaryIdx = boundary?.let { b -> messages.indexOfFirst { it.id == b.messageId } } ?: -1
        val sent = if (boundaryIdx >= 0) messages.drop(boundaryIdx + 1) else messages
        val summaryTokens = if (boundaryIdx >= 0) (contextSummary.orEmpty().length + 3) / 4 else 0
        val staleIdx = messages.indexOfLast {
            it.role == Role.Assistant && it.metrics?.contextTokens != null
        }
        val overhead = if (staleIdx >= 0) {
            (messages[staleIdx].metrics!!.contextTokens!! - estimate(messages.take(staleIdx + 1)))
                .coerceAtLeast(0)
        } else {
            0
        }
        return overhead + summaryTokens + estimate(sent)
    }

    init {
        // Rama git del workspace efectivo, cacheada por directorio: solo relanza `git`
        // cuando el workspace realmente cambia (gracias al distinctUntilChanged de arriba),
        // no en cada emisión de `state` (streaming, tokens…).
        viewModelScope.launch {
            effectiveWorkspaceFlow.collect { workspace ->
                if (workspace == null) {
                    _gitBranch.value = null
                    return@collect
                }
                val cached = gitBranchCache[workspace]
                if (cached != null || gitBranchCache.containsKey(workspace)) {
                    _gitBranch.value = cached
                    return@collect
                }
                val branch = currentGitBranch(workspace)
                gitBranchCache[workspace] = branch
                _gitBranch.value = branch
            }
        }

        // Auto-seleccionar la primera sesión si no hay activa. Dispara SOLO cuando
        // cambia la lista de sesiones (no cuando cambia activeSessionId): si
        // recombináramos también con activeSessionId, un `set(newId)` recién hecho
        // (p.ej. desde newSession()) se re-evaluaría contra un snapshot de `sessions`
        // que aún no incluye esa sesión (el flow de SQLDelight se re-emite async tras
        // el insert), la daríamos por inexistente y la pisaríamos con la anterior.
        viewModelScope.launch {
            chatRepository.sessionSummaries.collect { list ->
                val active = activeSessionStore.activeSessionId.value
                val valid = active != null && list.any { it.id == active }
                if (!valid) {
                    val fallback = list.firstOrNull()?.id
                    if (fallback != activeSessionStore.activeSessionId.value) {
                        activeSessionStore.set(fallback)
                    }
                }
            }
        }

        // Cuando cambian la conexión, el modelo configurado, o el modelo real devuelto
        // por el servidor (activeSession.model), re-fetchear el context length y si el
        // modelo configurado está cargado en memoria (para el aviso "Sin modelo cargado").
        viewModelScope.launch {
            combine(
                preferences.preferences.map { it.connection },
                state.map { it.activeSession?.model.orEmpty() }
            ) { cfg, sessionModel -> cfg to sessionModel }
                .distinctUntilChanged { a, b ->
                    a.first.baseUrl() == b.first.baseUrl() &&
                    a.first.model == b.first.model &&
                    a.second == b.second
                }
                .collect { refreshModelStatusNow() }
        }
    }

    /**
     * Re-consulta el estado del modelo saltándose el `distinctUntilChanged` de arriba.
     *
     * Hace falta porque ese colector solo reacciona a **cambios** de (baseUrl, modelo
     * configurado, modelo de la sesión), y cargar un modelo desde el selector no cambia
     * ninguno de los tres cuando el modelo que cargás ya era el configurado — que es el
     * caso típico: abrís la app con el modelo X configurado pero descargado (aviso "Sin
     * modelo cargado"), lo cargás, y como `cfg.model` sigue siendo X no se vuelve a
     * preguntar y el aviso se queda pegado para siempre.
     */
    fun refreshModelStatus() {
        viewModelScope.launch { refreshModelStatusNow() }
    }

    private suspend fun refreshModelStatusNow() {
        val cfg = preferences.current().connection
        if (!cfg.isValid()) {
            _tokensMax.value = DEFAULT_CONTEXT_LENGTH
            _modelLoaded.value = null
            return
        }
        val real = modelRepository.fetchContextLength(cfg.baseUrl(), cfg.model)
        _tokensMax.value = real ?: DEFAULT_CONTEXT_LENGTH
        _modelLoaded.value = modelRepository.isModelLoaded(cfg.baseUrl(), cfg.model)
    }

    /**
     * Un turno que termina bien es la prueba definitiva de que el modelo está cargado: acaba
     * de generar tokens. Prevalece sobre lo que dijera [ModelRepository.isModelLoaded], que
     * puede equivocarse por dos vías — el servidor todavía reporta la instancia como no
     * cargada justo tras un `loadModel`, o el id configurado no coincide literalmente con la
     * clave que devuelve el servidor y la comparación da `false` aunque el chat funcione.
     * Sin esto, el aviso seguía en pantalla mientras el modelo respondía.
     */
    private fun markModelLoadedOnSuccess(error: Throwable?) {
        if (error == null) _modelLoaded.value = true
    }

    private companion object {
        const val DEFAULT_CONTEXT_LENGTH = 8192
        const val DEFAULT_LANGUAGE_TAG = "es-ES"
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { textToSpeech.stop() }
    }

    fun onDraftChange(value: String) = _local.update { it.copy(draft = value) }

    fun selectSkill(skill: SkillDefinition) = _local.update { it.copy(pendingSkill = skill) }
    fun clearPendingSkill() = _local.update { it.copy(pendingSkill = null) }

    fun onImagePicked(bytes: ByteArray) = _local.update { it.copy(attachedImageBytes = bytes) }
    fun clearAttachment() = _local.update { it.copy(attachedImageBytes = null) }

    fun attachTextFile(file: com.localchatbot.core.fs.AttachedTextFile) =
        _local.update { it.copy(attachedTextFiles = it.attachedTextFiles + file) }

    fun attachTextFileError(message: String) =
        _local.update { it.copy(errorMessage = message) }

    fun removeTextFile(name: String) =
        _local.update { it.copy(attachedTextFiles = it.attachedTextFiles.filter { f -> f.name != name }) }

    fun newSession() {
        viewModelScope.launch {
            val session = createSessionUseCase()
            activeSessionStore.set(session.id)
        }
    }

    /**
     * Notificación de sistema (solo desktop) al terminar un turno de chat: éxito o
     * fallo. Se omite si el usuario canceló el stream (no es un "final" real) o si
     * desactivó las notificaciones en Ajustes.
     */
    private suspend fun notifyChatFinished(sessionId: String, error: Throwable?) {
        if (error is kotlinx.coroutines.CancellationException) return
        if (!preferences.current().desktopNotificationsEnabled) return
        val title = chatRepository.getSession(sessionId)?.title
            ?.takeIf { it.isNotBlank() } ?: "LocalChatBot"
        val body = if (error == null) "Respuesta lista" else "La respuesta no se pudo completar"
        systemNotifier.notify(title, body)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun send() {
        val current = _local.value
        val rawText = current.draft.trim()

        // Comando del composer escrito a mano: se ejecuta en vez de enviarse al modelo.
        // Misma ruta que elegirlo del popup (ver [runSlashCommand]).
        SlashCommand.parse(rawText)?.let { cmd ->
            _local.update { it.copy(draft = "", errorMessage = null) }
            runSlashCommand(cmd)
            return
        }

        val image = current.attachedImageBytes
        val pendingSkill = current.pendingSkill
        val textFiles = current.attachedTextFiles
        val activeId = activeSessionStore.activeSessionId.value
        // Con un turno en curso el mensaje no se pierde: va a la cola y se enviará fusionado
        // con el resto al terminar. Solo texto — durante el turno los botones de adjuntar
        // están deshabilitados, así que aquí no hay imagen ni archivos que arrastrar.
        if (activeId != null && streamingStateStore.isStreaming(activeId)) {
            if (rawText.isEmpty()) return
            queuedMessageStore.enqueue(activeId, rawText)
            _local.update { it.copy(draft = "", errorMessage = null) }
            return
        }
        if (rawText.isEmpty() && image == null && textFiles.isEmpty()) return

        val dataUrl = image?.let { "data:image/jpeg;base64,${Base64.encode(it)}" }

        // Los archivos adjuntados NO se incrustan en el texto del mensaje (así no se
        // muestran crudos en la burbuja); viajan como adjuntos y el use case los expande
        // a bloques fenced solo en el payload para el modelo.
        val attachments = textFiles.map {
            com.localchatbot.domain.model.MessageAttachment(it.name, it.content)
        }
        // Texto visible de la burbuja: lo que escribió el usuario; "(imagen)" si solo
        // hay imagen; vacío si solo hay adjuntos (la burbuja mostrará sus chips).
        val text = when {
            rawText.isNotBlank() -> rawText
            image != null -> "(imagen)"
            else -> ""
        }

        // Limpiar input al instante para feedback. pendingSkill se mantiene activo hasta
        // que el usuario pulse la X del badge — permite skills persistentes como caveman.
        _local.update { it.copy(draft = "", errorMessage = null, attachedImageBytes = null, attachedTextFiles = emptyList()) }

        val systemPromptOverride = pendingSkill?.let { skill ->
            buildString {
                append(skill.systemPromptAddition)
                append("\n\n---\n")
                append("The user invoked this skill by typing \"/${skill.id}\". ")
                append("Their message may begin with invocation arguments (a level or option documented above). ")
                append("Treat leading tokens that match documented arguments as configuration, not as the subject of the request. ")
                append("If the message contains ONLY argument tokens and no actual question or task, briefly acknowledge the active configuration and wait for the user's next message.")
            }
        }

        startTurn(activeId, text, dataUrl, systemPromptOverride, attachments)
    }

    /**
     * Lanza un turno. Extraído de [send] para que el envío normal y el vaciado de la cola
     * ([drainQueueIfAny], [sendQueuedNow]) usen la misma ruta en vez de duplicar el
     * lanzamiento del stream.
     *
     * @param allowCreateSession si la sesión indicada ya no existe, crear una nueva. True
     *   cuando el usuario acaba de pulsar enviar; false al vaciar la cola, porque resucitar
     *   una conversación borrada para volcarle mensajes viejos sería sorprendente.
     */
    private fun startTurn(
        activeId: String?,
        text: String,
        dataUrl: String?,
        systemPromptOverride: String?,
        attachments: List<com.localchatbot.domain.model.MessageAttachment>,
        allowCreateSession: Boolean = true
    ) {
        // El stream se lanza en applicationScope: sobrevive a la destrucción de este VM.
        // Además pedimos al SO mantener el proceso vivo mientras dure el stream.
        streamJob = applicationScope.launch {
            // Si la sesión activa fue borrada mientras estábamos en ella, activeId apunta
            // a una sesión inexistente → crear una nueva en vez de fallar con
            // "session not found". Cubre también la carrera del auto-select tras borrar.
            val existing = activeId?.takeIf { chatRepository.getSession(it) != null }
            val sessionId = existing
                ?: if (allowCreateSession) {
                    createSessionUseCase().id.also(activeSessionStore::set)
                } else {
                    // Cola de una sesión que ya no existe: se descarta sin más.
                    activeId?.let(queuedMessageStore::clear)
                    return@launch
                }
            // Esta respuesta cierra cualquier pregunta `ask_user` pendiente de la sesión.
            pendingUserPromptStore.clear(sessionId)
            streamingStateStore.start(sessionId)
            backgroundExecutor.start("chat-stream-$sessionId")
            var failure: Throwable? = null
            try {
                val result = sendMessageUseCase(
                    sessionId,
                    text,
                    dataUrl,
                    systemPromptOverride,
                    attachments
                )
                failure = result.exceptionOrNull()
                failure?.let { e ->
                    _local.update { it.copy(errorMessage = friendlyStreamErrorMessage(e)) }
                }
                markModelLoadedOnSuccess(failure)
                notifyChatFinished(sessionId, failure)
            } finally {
                streamingStateStore.stop(sessionId)
                backgroundExecutor.stop()
            }
            // FUERA del finally a propósito: dentro, la sesión seguiría marcada como
            // "streaming" y el mensaje fusionado se volvería a encolar a sí mismo, en bucle
            // infinito. Aquí además la cancelación (botón Stop) ya no llega, que es
            // justamente lo que queremos: frenar al modelo no debe disparar otro turno.
            drainQueueIfAny(sessionId, failure)
        }
    }

    /**
     * Envía la cola de [sessionId] fusionada en un solo mensaje, si procede.
     *
     * No se vacía sola cuando el turno falló (querés ver el error y decidir) ni cuando el
     * modelo dejó una pregunta `ask_user` abierta: lo encolado se escribió *antes* de que la
     * pregunta existiera, así que mandarlo como respuesta sería contestar otra cosa. En esos
     * casos la cola se queda quieta y el contenedor ofrece "Enviar ahora".
     */
    private fun drainQueueIfAny(sessionId: String, error: Throwable?) {
        if (error != null) return
        if (pendingUserPromptStore.promptFor(sessionId) != null) return
        sendQueuedNow(sessionId)
    }

    /** Vacía la cola de [sessionId] y la envía fusionada. No hace nada si está vacía. */
    fun sendQueuedNow(sessionId: String? = activeSessionStore.activeSessionId.value) {
        val id = sessionId ?: return
        if (streamingStateStore.isStreaming(id)) return
        val queued = queuedMessageStore.drain(id)
        if (queued.isEmpty()) return
        startTurn(
            activeId = id,
            text = queued.joinToString("\n\n") { it.text },
            dataUrl = null,
            systemPromptOverride = null,
            attachments = emptyList(),
            allowCreateSession = false
        )
    }

    /** Quita un mensaje de la cola antes de que llegue a enviarse. */
    fun removeQueued(messageId: String) {
        val activeId = activeSessionStore.activeSessionId.value ?: return
        queuedMessageStore.remove(activeId, messageId)
    }

    /**
     * Responde a una pregunta `ask_user` seleccionando una opción: la coloca en el
     * draft y la envía como un mensaje normal del usuario.
     */
    fun submitPromptOption(option: String) {
        if (option.isBlank()) return
        _local.update { it.copy(draft = option) }
        send()
    }

    /**
     * Regenera la respuesta del assistant más reciente: localiza el último mensaje del usuario
     * y delega en [resendMessage] (que borra desde ahí en adelante y vuelve a invocar al modelo).
     */
    fun regenerateLastResponse() {
        val session = state.value.activeSession ?: return
        val lastUserMsg = session.messages.lastOrNull { it.role == com.localchatbot.domain.model.Role.User }
            ?: return
        resendMessage(lastUserMsg.id)
    }

    /** Cancela el stream en curso (si lo hay). */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * Reenvía un mensaje del usuario ya existente: elimina ese mensaje y todo lo posterior
     * (respuestas potencialmente obsoletas) y vuelve a invocar el modelo con el mismo contenido.
     *
     * **Bifurcación:** si después de [messageId] hay más turnos del usuario, truncar tiraría
     * una conversación entera, así que antes se guarda una copia completa como sesión aparte
     * (sección "Ramas anteriores" del drawer) y el usuario sigue donde estaba. Cuando lo que
     * se descarta es sólo la respuesta al último mensaje — el caso de "regenerar" — no se
     * copia nada: es justo la respuesta que se está pidiendo reemplazar.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun resendMessage(messageId: String) {
        val activeId = activeSessionStore.activeSessionId.value ?: return
        if (streamingStateStore.isStreaming(activeId)) return
        val session = state.value.activeSession ?: return
        if (session.id != activeId) return
        val msg = session.messages.firstOrNull { it.id == messageId } ?: return
        if (msg.role != com.localchatbot.domain.model.Role.User) return

        val text = msg.content
        val dataUrl = msg.imageDataUrl
        val attachments = msg.attachments
        // ¿Se descarta algo más que la(s) respuesta(s) a este mismo mensaje?
        val discardsLaterTurns = session.messages
            .dropWhile { it.id != messageId }
            .drop(1)
            .any { it.role == com.localchatbot.domain.model.Role.User }

        streamJob = applicationScope.launch {
            if (discardsLaterTurns) {
                // Si la copia falla, NO truncamos: el sentido de bifurcar es no perder la
                // conversación, así que ante la duda se queda todo como está y se avisa.
                val branch = runCatching { chatRepository.forkSession(activeId) }.getOrNull()
                if (branch == null) {
                    _local.update {
                        it.copy(errorMessage = "No se pudo guardar la rama anterior; no se reenvió nada")
                    }
                    return@launch
                }
                // Que no acabe en la sección de ramas es cosmético (aparecería como una
                // conversación normal): no justifica abortar el reenvío.
                runCatching {
                    projectRepository.assignSession(branch.id, ProjectRepository.BRANCHES_GROUP_ID)
                }
            }
            chatRepository.deleteMessagesFrom(activeId, messageId)
            streamingStateStore.start(activeId)
            backgroundExecutor.start("chat-stream-$activeId")
            try {
                val result = sendMessageUseCase(
                    activeId,
                    text.ifBlank { if (dataUrl != null) "(imagen)" else "" },
                    dataUrl,
                    attachments = attachments
                )
                result.exceptionOrNull()?.let { e ->
                    _local.update { it.copy(errorMessage = friendlyStreamErrorMessage(e)) }
                }
                markModelLoadedOnSuccess(result.exceptionOrNull())
                notifyChatFinished(activeId, result.exceptionOrNull())
            } finally {
                streamingStateStore.stop(activeId)
                backgroundExecutor.stop()
            }
        }
    }

    /**
     * Carga el contenido (texto e imagen) de un mensaje del usuario en el composer
     * para que el usuario pueda modificarlo y enviarlo como **mensaje nuevo**.
     *
     * **No toca el historial**: el mensaje original y todas las respuestas
     * posteriores quedan intactos. Al pulsar enviar, el texto se manda como un
     * mensaje nuevo al final de la conversación, igual que si lo hubieras
     * escrito desde cero.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun editMessage(messageId: String) {
        val activeId = activeSessionStore.activeSessionId.value ?: return
        if (streamingStateStore.isStreaming(activeId)) return
        val session = state.value.activeSession ?: return
        if (session.id != activeId) return
        val msg = session.messages.firstOrNull { it.id == messageId } ?: return
        if (msg.role != com.localchatbot.domain.model.Role.User) return

        val text = if (msg.content == "(imagen)") "" else msg.content
        val imageBytes = msg.imageDataUrl
            ?.substringAfter("base64,", missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }

        _local.update {
            it.copy(
                draft = text,
                attachedImageBytes = imageBytes,
                errorMessage = null
            )
        }
    }

    fun savePromptTemplates(list: List<com.localchatbot.domain.model.PromptTemplate>) {
        applicationScope.launch { preferences.setPromptTemplates(list) }
    }

    /**
     * Cambia el workspace desde la barra del chat. Si la sesión activa pertenece a un
     * proyecto, actualiza la carpeta de ESE proyecto (coherente con lo que muestra el chip);
     * si no, cambia el workspace global. Limpiar (value null) solo aplica al global.
     */
    fun updateFsWorkspaceDir(value: String?) {
        applicationScope.launch {
            val activeId = activeSessionStore.activeSessionId.value
            val projectId = activeId?.let { projectRepository.current().assignments[it] }
            if (projectId != null && value != null) {
                projectRepository.updateWorkspace(projectId, value)
            } else {
                preferences.updateFsWorkspaceDir(value)
            }
        }
    }

    /** Toggle del modo YOLO desde la barra del chat. */
    fun toggleFsYoloMode() {
        applicationScope.launch {
            val current = preferences.current().fsYoloMode
            preferences.updateFsYoloMode(!current)
        }
    }

    fun toggleFsPreviewEdits() {
        applicationScope.launch {
            val current = preferences.current().fsPreviewEdits
            preferences.updateFsPreviewEdits(!current)
        }
    }

    /**
     * Alterna entre modo Plan (solo lectura) y Build (puede escribir) para la sesión activa.
     * El modo se guarda como override por sesión; si no hay sesión activa, cambia el global.
     */
    fun toggleAgentMode() {
        applicationScope.launch {
            val activeId = activeSessionStore.activeSessionId.value
            val prefs = preferences.current()
            val current = activeId?.let { prefs.sessionAgentModes[it] } ?: prefs.agentMode
            val next = if (current == com.localchatbot.domain.model.AgentMode.Plan) {
                com.localchatbot.domain.model.AgentMode.Build
            } else {
                com.localchatbot.domain.model.AgentMode.Plan
            }
            if (activeId != null) preferences.updateSessionAgentMode(activeId, next)
            else preferences.updateAgentMode(next)
        }
    }

    /**
     * Toggle del sandbox: la flag persistida es `fsAllowOutsideWorkspace` (true =
     * sandbox apagado). El chip de la UI muestra "Sandbox ON" cuando
     * allowOutside es false, así que aquí simplemente invertimos el flag.
     */
    fun toggleFsSandbox() {
        applicationScope.launch {
            val current = preferences.current().fsAllowOutsideWorkspace
            preferences.updateFsAllowOutsideWorkspace(!current)
        }
    }

    /** Guarda los bytes recibidos como imagen en la galería del dispositivo. */
    fun saveImage(bytes: ByteArray) {
        applicationScope.launch {
            val filename = "localchatbot_${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}.png"
            val ok = imageSaver.saveToGallery(bytes, filename)
            _local.update {
                it.copy(errorMessage = if (ok) null else "No se pudo guardar la imagen")
            }
        }
    }

    fun dismissError() = _local.update { it.copy(errorMessage = null) }

    // --- Comandos del composer ---------------------------------------------------------

    /**
     * Texto que la UI debe copiar al portapapeles. El portapapeles solo se alcanza desde
     * Compose (`LocalClipboardManager`), así que el VM lo pide y `ChatScreen` lo cumple y
     * llama a [consumeClipboardRequest].
     */
    private val _clipboardRequest = MutableStateFlow<String?>(null)
    val clipboardRequest: StateFlow<String?> = _clipboardRequest

    fun consumeClipboardRequest() {
        _clipboardRequest.value = null
    }

    /**
     * Ejecuta un comando `/`. Punto único para las dos formas de invocarlo — elegirlo del
     * popup o escribirlo y pulsar Enter — para que no puedan divergir.
     */
    fun runSlashCommand(command: SlashCommand) {
        when (command) {
            SlashCommand.Compact -> requestCompact()
            SlashCommand.UndoCompact -> undoCompact()
            SlashCommand.NewSession -> newSession()
            SlashCommand.Init -> requestInitProject()
            SlashCommand.Export -> {
                val markdown = activeSessionMarkdown()
                if (markdown == null) {
                    _local.update { it.copy(errorMessage = "No hay conversación que exportar") }
                } else {
                    _clipboardRequest.value = markdown
                }
            }
        }
    }

    /** Comandos ofrecibles ahora mismo, para el popup del composer. */
    fun availableSlashCommands(): List<SlashCommand> {
        val current = state.value
        return SlashCommand.availableFor(
            hasMessages = current.activeSession?.messages?.isNotEmpty() == true,
            compacted = current.contextCompacted,
            initAvailable = initProjectUseCase != null &&
                PlatformCapabilities.isDesktop &&
                current.fsWorkspaceDir != null
        )
    }

    // --- Exportar conversación (3.2) ---------------------------------------------------

    /** Markdown de la conversación activa, o null si no hay ninguna con mensajes. */
    fun activeSessionMarkdown(): String? {
        val session = state.value.activeSession?.takeIf { it.messages.isNotEmpty() } ?: return null
        return ChatExport.sessionToMarkdown(session, exportedAt = formatNow())
    }

    /** Markdown de un turno suelto (mensaje de usuario + su respuesta), para copiar. */
    fun turnMarkdown(messageId: String): String? {
        val session = state.value.activeSession ?: return null
        return ChatExport.turnToMarkdown(session.messages, messageId)
    }

    /**
     * Guarda la conversación como `.md` mediante el diálogo nativo (solo desktop; en móvil
     * [saveTextFile] devuelve null y la UI no ofrece la acción). El feedback va por el
     * banner que ya se usa para errores.
     */
    fun exportActiveSessionToFile() {
        val session = state.value.activeSession?.takeIf { it.messages.isNotEmpty() } ?: return
        val markdown = ChatExport.sessionToMarkdown(session, exportedAt = formatNow())
        viewModelScope.launch {
            val path = saveTextFile(ChatExport.suggestedFileName(session.title), markdown)
            _local.update {
                it.copy(
                    errorMessage = if (path != null) "Conversación guardada en $path"
                    else "No se guardó la conversación"
                )
            }
        }
    }

    /** Confirmación de que algo se copió, por el mismo banner que los errores. */
    fun notifyCopied(what: String) = _local.update { it.copy(errorMessage = "$what copiado al portapapeles") }

    /** `yyyy-MM-dd HH:mm` en la zona local. kotlinx-datetime no trae formateo de patrones. */
    private fun formatNow(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        fun pad(n: Int) = n.toString().padStart(2, '0')
        return "${now.year}-${pad(now.monthNumber)}-${pad(now.dayOfMonth)} ${pad(now.hour)}:${pad(now.minute)}"
    }

    /** Snapshot combinado de sesión activa + preferencias + proyectos para construir el estado. */
    private data class SessionData(
        /** Solo la sesión activa: el resto del historial no hace falta para pintar el chat. */
        val active: ChatSession?,
        val activeId: String?,
        val prefs: com.localchatbot.domain.model.AppPreferences,
        val projectState: com.localchatbot.domain.model.ProjectState
    )

    /** Snapshot combinado de tokens/modelo cargado/cola/rama git para construir el estado. */
    private data class TokensState(
        val tokensMax: Int,
        val modelLoaded: Boolean?,
        val queuedBySession: Map<String, List<QueuedMessage>>,
        val gitBranch: String?
    )

    private data class LocalState(
        val draft: String = "",
        val errorMessage: String? = null,
        val attachedImageBytes: ByteArray? = null,
        val pendingSkill: SkillDefinition? = null,
        val attachedTextFiles: List<com.localchatbot.core.fs.AttachedTextFile> = emptyList()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LocalState) return false
            return draft == other.draft &&
                errorMessage == other.errorMessage &&
                attachedImageBytes.contentEqualsOrNull(other.attachedImageBytes) &&
                pendingSkill == other.pendingSkill &&
                attachedTextFiles == other.attachedTextFiles
        }

        override fun hashCode(): Int {
            var result = draft.hashCode()
            result = 31 * result + (errorMessage?.hashCode() ?: 0)
            result = 31 * result + (attachedImageBytes?.contentHashCode() ?: 0)
            result = 31 * result + (pendingSkill?.hashCode() ?: 0)
            result = 31 * result + attachedTextFiles.hashCode()
            return result
        }
    }
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}
