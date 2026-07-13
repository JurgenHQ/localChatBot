package com.localchatbot.presentation.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.background.BackgroundExecutor
import com.localchatbot.core.image.ImageSaver
import com.localchatbot.core.network.friendlyStreamErrorMessage
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.PendingUserPrompt
import com.localchatbot.core.state.PendingUserPromptStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.core.storage.CheckpointStore
import com.localchatbot.core.platform.SystemNotifier
import com.localchatbot.core.voice.TextToSpeech
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.skill.SkillCatalog
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val modelLoaded: Boolean? = null
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
    private val applicationScope: CoroutineScope,
    private val backgroundExecutor: BackgroundExecutor,
    private val createSessionUseCase: CreateSessionUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val modelRepository: ModelRepository,
    private val imageSaver: ImageSaver,
    private val textToSpeech: TextToSpeech,
    private val systemNotifier: SystemNotifier,
    private val checkpointStore: CheckpointStore? = null
) : ViewModel() {

    // ID del mensaje que se está leyendo en voz alta (null = ninguno).
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId

    /**
     * Revert pendiente de confirmación: el usuario pulsó el chip "revertir este
     * turno" y el diálogo muestra los archivos que se restaurarían.
     */
    data class PendingRevert(val checkpointId: String, val files: List<String>)

    private val _pendingRevert = MutableStateFlow<PendingRevert?>(null)
    val pendingRevert: StateFlow<PendingRevert?> = _pendingRevert

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

    val state: StateFlow<ChatUiState> = combine(
        combine(
            chatRepository.sessions,
            activeSessionStore.activeSessionId,
            preferences.preferences,
            projectRepository.state
        ) { sessions, activeId, prefs, projectState -> SessionData(sessions, activeId, prefs, projectState) },
        combine(
            streamingStateStore.streaming,
            streamingStateStore.activity,
            streamingStateStore.toolCallLog
        ) { streaming, activity, logs -> Triple(streaming, activity, logs) },
        _local,
        combine(_tokensMax, _modelLoaded) { tokensMax, modelLoaded -> tokensMax to modelLoaded }
    ) { sessionData, streamTriple, local, tokensState ->
        val (tokensMax, modelLoaded) = tokensState
        val (sessions, activeId, prefs, projectState) = sessionData
        val (streamingIds, activityMap, toolCallLogs) = streamTriple
        // Workspace y modo EFECTIVOS de la sesión activa (carpeta del proyecto o global;
        // override de modo por sesión o el global). Así los chips reflejan la sesión actual.
        val effectiveWorkspace = activeId
            ?.let { projectState.assignments[it] }
            ?.let { pid -> projectState.projects.firstOrNull { it.id == pid } }
            ?.workspaceDir ?: prefs.fsWorkspaceDir
        val effectiveAgentMode = activeId?.let { prefs.sessionAgentModes[it] } ?: prefs.agentMode
        val active = sessions.firstOrNull { it.id == activeId }
        // Tokens de contexto, lo más realista posible:
        // - Si hay una respuesta del modelo con `contextTokens` reales (prompt_tokens de
        //   su última llamada), usamos ESE número como base — incluye system prompt,
        //   definiciones de tools, resultados de tools e historial, tal como los contó
        //   el servidor.
        // - Le sumamos un estimado (chars/4) de los mensajes posteriores que aún no se
        //   respondieron (típicamente un mensaje nuevo del usuario).
        // - Si todavía no hay métricas reales, estimamos TODO por longitud (incluyendo
        //   resultados de tools, que sí ocupan contexto). Las imágenes van out-of-band.
        val tokensUsed = computeContextTokens(active?.messages.orEmpty())
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
            pendingSkill = local.pendingSkill,
            attachedTextFiles = local.attachedTextFiles,
            installedEnabledSkills = installedEnabled,
            modelLoaded = modelLoaded
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /**
     * Estima cuántos tokens ocupa el contexto actual. Usa los `contextTokens` reales
     * del último mensaje del modelo (incluyen system prompt, tools y resultados, tal
     * como los contó el servidor) y le suma un estimado de los mensajes posteriores
     * aún sin responder. Si no hay métricas reales todavía, estima todo por longitud.
     */
    private fun computeContextTokens(messages: List<ChatMessage>): Int {
        if (messages.isEmpty()) return 0
        fun estimate(msgs: List<ChatMessage>): Int = (msgs.sumOf { it.content.length } + 3) / 4
        val lastRealIdx = messages.indexOfLast {
            it.role == Role.Assistant && it.metrics?.contextTokens != null
        }
        return if (lastRealIdx >= 0) {
            messages[lastRealIdx].metrics!!.contextTokens!! + estimate(messages.drop(lastRealIdx + 1))
        } else {
            estimate(messages)
        }
    }

    init {
        // Auto-seleccionar la primera sesión si no hay activa.
        viewModelScope.launch {
            combine(chatRepository.sessions, activeSessionStore.activeSessionId) { list, active ->
                // Validar que la sesión activa siga existiendo: si fue borrada (o el
                // auto-select la fijó desde una lista aún no propagada), caer a la
                // primera disponible, o a null si no quedan → pantalla inicial.
                if (active != null && list.any { it.id == active }) active
                else list.firstOrNull()?.id
            }.collect { id ->
                if (id != activeSessionStore.activeSessionId.value) {
                    activeSessionStore.set(id)
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
                .collect { (cfg, _) ->
                    if (!cfg.isValid()) {
                        _tokensMax.value = DEFAULT_CONTEXT_LENGTH
                        _modelLoaded.value = null
                        return@collect
                    }
                    val real = modelRepository.fetchContextLength(cfg.baseUrl(), cfg.model)
                    _tokensMax.value = real ?: DEFAULT_CONTEXT_LENGTH
                    _modelLoaded.value = modelRepository.isModelLoaded(cfg.baseUrl(), cfg.model)
                }
        }
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
        val image = current.attachedImageBytes
        val pendingSkill = current.pendingSkill
        val textFiles = current.attachedTextFiles
        val activeId = activeSessionStore.activeSessionId.value
        if (activeId != null && streamingStateStore.isStreaming(activeId)) return
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

        // El stream se lanza en applicationScope: sobrevive a la destrucción de este VM.
        // Además pedimos al SO mantener el proceso vivo mientras dure el stream.
        streamJob = applicationScope.launch {
            // Si la sesión activa fue borrada mientras estábamos en ella, activeId apunta
            // a una sesión inexistente → crear una nueva en vez de fallar con
            // "session not found". Cubre también la carrera del auto-select tras borrar.
            val sessionId = activeId?.takeIf { chatRepository.getSession(it) != null }
                ?: createSessionUseCase().id.also(activeSessionStore::set)
            // Esta respuesta cierra cualquier pregunta `ask_user` pendiente de la sesión.
            pendingUserPromptStore.clear(sessionId)
            streamingStateStore.start(sessionId)
            backgroundExecutor.start("chat-stream-$sessionId")
            try {
                val result = sendMessageUseCase(
                    sessionId,
                    text,
                    dataUrl,
                    systemPromptOverride,
                    attachments
                )
                result.exceptionOrNull()?.let { e ->
                    _local.update { it.copy(errorMessage = friendlyStreamErrorMessage(e)) }
                }
                notifyChatFinished(sessionId, result.exceptionOrNull())
            } finally {
                streamingStateStore.stop(sessionId)
                backgroundExecutor.stop()
            }
        }
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

        streamJob = applicationScope.launch {
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

    /** Snapshot combinado de sesión activa + preferencias + proyectos para construir el estado. */
    private data class SessionData(
        val sessions: List<ChatSession>,
        val activeId: String?,
        val prefs: com.localchatbot.domain.model.AppPreferences,
        val projectState: com.localchatbot.domain.model.ProjectState
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
