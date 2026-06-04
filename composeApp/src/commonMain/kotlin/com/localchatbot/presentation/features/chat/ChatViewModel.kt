package com.localchatbot.presentation.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.background.BackgroundExecutor
import com.localchatbot.core.image.ImageSaver
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
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
    /** Tokens estimados de la conversación (≈ chars/4). */
    val tokensUsed: Int = 0,
    /** Tamaño máximo del contexto asumido para el indicador visual. */
    val tokensMax: Int = 8192,
    val promptTemplates: List<com.localchatbot.domain.model.PromptTemplate> = emptyList(),
    /**
     * Sugerencias dinámicas para el empty state, generadas por el modelo tras
     * cada respuesta exitosa. Si null, la UI muestra una lista estática.
     */
    val dynamicSuggestions: List<String>? = null,
    /** Workspace configurado para las tools de filesystem (null = sin configurar). */
    val fsWorkspaceDir: String? = null,
    /** Si true, las tools de fs se ejecutan sin diálogo de confirmación. */
    val fsYoloMode: Boolean = false,
    /** Si true, las tools de fs aceptan paths fuera del workspace. */
    val fsAllowOutsideWorkspace: Boolean = false
) {
    val hasAttachment: Boolean get() = attachedImageBytes != null
}

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val preferences: PreferencesRepository,
    private val activeSessionStore: ActiveSessionStore,
    private val streamingStateStore: StreamingStateStore,
    private val applicationScope: CoroutineScope,
    private val backgroundExecutor: BackgroundExecutor,
    private val createSessionUseCase: CreateSessionUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val modelRepository: ModelRepository,
    private val imageSaver: ImageSaver
) : ViewModel() {

    private val _local = MutableStateFlow(LocalState())

    /** Job del stream actualmente en curso, para poder cancelarlo desde la UI. */
    private var streamJob: Job? = null

    /** Longitud de contexto real del modelo cargado (8192 como fallback). */
    private val _tokensMax = MutableStateFlow(DEFAULT_CONTEXT_LENGTH)

    /**
     * Cache en memoria de las 3 sugerencias dinámicas del empty state.
     * - null mientras nunca se hayan generado: la UI cae al fallback estático.
     * - tras la primera respuesta exitosa, se refresca en background.
     */
    private val _suggestions = MutableStateFlow<List<String>?>(null)

    /** Job de la generación en curso para evitar dobles llamadas concurrentes. */
    private var suggestionsJob: Job? = null

    val state: StateFlow<ChatUiState> = combine(
        combine(
            chatRepository.sessions,
            activeSessionStore.activeSessionId,
            preferences.preferences
        ) { sessions, activeId, prefs -> Triple(sessions, activeId, prefs) },
        streamingStateStore.streaming,
        streamingStateStore.activity,
        _local,
        combine(_tokensMax, _suggestions) { tokensMax, suggestions -> tokensMax to suggestions }
    ) { (sessions, activeId, prefs), streamingIds, activityMap, local, tokensAndSuggestions ->
        val (tokensMax, dynamicSuggestions) = tokensAndSuggestions
        val active = sessions.firstOrNull { it.id == activeId }
        // Las imágenes (imageDataUrl) se adjuntan out-of-band: no pasan por el contexto
        // del modelo ni consumen tokens, así que solo contamos el texto.
        val totalChars = active?.messages
            ?.filter { it.role != com.localchatbot.domain.model.Role.Tool }
            ?.sumOf { it.content.length }
            ?: 0
        ChatUiState(
            activeSession = active,
            modelName = prefs.connection.model,
            draft = local.draft,
            sending = activeId != null && activeId in streamingIds,
            errorMessage = local.errorMessage,
            attachedImageBytes = local.attachedImageBytes,
            toolActivity = activeId?.let(activityMap::get),
            tokensUsed = (totalChars + 3) / 4,
            tokensMax = tokensMax,
            promptTemplates = prefs.promptTemplates,
            dynamicSuggestions = dynamicSuggestions,
            fsWorkspaceDir = prefs.fsWorkspaceDir,
            fsYoloMode = prefs.fsYoloMode,
            fsAllowOutsideWorkspace = prefs.fsAllowOutsideWorkspace
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    init {
        // Auto-seleccionar la primera sesión si no hay activa.
        viewModelScope.launch {
            combine(chatRepository.sessions, activeSessionStore.activeSessionId) { list, active ->
                if (active == null) list.firstOrNull()?.id else active
            }.collect { id ->
                if (id != activeSessionStore.activeSessionId.value) {
                    activeSessionStore.set(id)
                }
            }
        }

        // Cuando cambian la conexión o el modelo, intentar leer el context length real
        // del servidor (LM Studio lo expone). Si no, mantenemos el default.
        viewModelScope.launch {
            preferences.preferences
                .map { it.connection }
                .distinctUntilChanged { a, b -> a.baseUrl() == b.baseUrl() && a.model == b.model }
                .collect { cfg ->
                    if (!cfg.isValid()) {
                        _tokensMax.value = DEFAULT_CONTEXT_LENGTH
                        return@collect
                    }
                    val real = modelRepository.fetchContextLength(cfg.baseUrl(), cfg.model)
                    _tokensMax.value = real ?: DEFAULT_CONTEXT_LENGTH
                }
        }

        // Genera sugerencias dinámicas una sola vez al arrancar el VM.
        // Mientras la app esté abierta el valor queda en memoria y no se vuelve a pedir.
        refreshSuggestions()
    }

    private companion object {
        const val DEFAULT_CONTEXT_LENGTH = 8192
    }

    fun onDraftChange(value: String) = _local.update { it.copy(draft = value) }

    fun onImagePicked(bytes: ByteArray) = _local.update { it.copy(attachedImageBytes = bytes) }
    fun clearAttachment() = _local.update { it.copy(attachedImageBytes = null) }

    fun newSession() {
        viewModelScope.launch {
            val session = createSessionUseCase()
            activeSessionStore.set(session.id)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun send() {
        val current = _local.value
        val text = current.draft.trim()
        val image = current.attachedImageBytes
        val activeId = activeSessionStore.activeSessionId.value
        if (activeId != null && streamingStateStore.isStreaming(activeId)) return
        if (text.isEmpty() && image == null) return

        val dataUrl = image?.let { "data:image/jpeg;base64,${Base64.encode(it)}" }

        // Limpiar input al instante para feedback.
        _local.update { it.copy(draft = "", errorMessage = null, attachedImageBytes = null) }

        // El stream se lanza en applicationScope: sobrevive a la destrucción de este VM.
        // Además pedimos al SO mantener el proceso vivo mientras dure el stream.
        streamJob = applicationScope.launch {
            val sessionId = activeId ?: createSessionUseCase().id.also(activeSessionStore::set)
            streamingStateStore.start(sessionId)
            backgroundExecutor.start("chat-stream-$sessionId")
            try {
                val result = sendMessageUseCase(sessionId, text.ifBlank { "(imagen)" }, dataUrl)
                result.exceptionOrNull()?.message?.let { msg ->
                    _local.update { it.copy(errorMessage = msg) }
                }
            } finally {
                streamingStateStore.stop(sessionId)
                backgroundExecutor.stop()
            }
        }
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

        streamJob = applicationScope.launch {
            chatRepository.deleteMessagesFrom(activeId, messageId)
            streamingStateStore.start(activeId)
            backgroundExecutor.start("chat-stream-$activeId")
            try {
                val result = sendMessageUseCase(activeId, text.ifBlank { "(imagen)" }, dataUrl)
                result.exceptionOrNull()?.message?.let { errMsg ->
                    _local.update { it.copy(errorMessage = errMsg) }
                }
            } finally {
                streamingStateStore.stop(activeId)
                backgroundExecutor.stop()
            }
        }
    }

    /**
     * Carga el contenido (texto e imagen) de un mensaje del usuario en el composer para editarlo,
     * eliminándolo junto con sus respuestas. Tras editar y pulsar enviar, se manda como nuevo.
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

        applicationScope.launch {
            chatRepository.deleteMessagesFrom(activeId, messageId)
            _local.update {
                it.copy(
                    draft = text,
                    attachedImageBytes = imageBytes,
                    errorMessage = null
                )
            }
        }
    }

    fun savePromptTemplates(list: List<com.localchatbot.domain.model.PromptTemplate>) {
        applicationScope.launch { preferences.setPromptTemplates(list) }
    }

    /** Cambia el workspace para las fs tools desde la barra del chat. */
    fun updateFsWorkspaceDir(value: String?) {
        applicationScope.launch { preferences.updateFsWorkspaceDir(value) }
    }

    /** Toggle del modo YOLO desde la barra del chat. */
    fun toggleFsYoloMode() {
        applicationScope.launch {
            val current = preferences.current().fsYoloMode
            preferences.updateFsYoloMode(!current)
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

    /**
     * Pide al modelo 3 sugerencias frescas para el empty state. Se ignora si
     * ya hay una llamada en curso o si la conexión no está configurada.
     * Se ejecuta en applicationScope para que no se cancele al destruir el VM.
     */
    private fun refreshSuggestions() {
        if (suggestionsJob?.isActive == true) return
        suggestionsJob = applicationScope.launch {
            val cfg = preferences.current().connection
            if (!cfg.isValid()) return@launch
            modelRepository.generateSuggestions(cfg.baseUrl(), cfg.model)
                .onSuccess { list -> _suggestions.value = list }
            // Si falla, mantenemos las sugerencias previas (o el fallback estático
            // si nunca se generaron). No mostramos error al usuario — es un
            // refresco best-effort en background, no algo que pidió.
        }
    }

    private data class LocalState(
        val draft: String = "",
        val errorMessage: String? = null,
        val attachedImageBytes: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LocalState) return false
            return draft == other.draft &&
                errorMessage == other.errorMessage &&
                attachedImageBytes.contentEqualsOrNull(other.attachedImageBytes)
        }

        override fun hashCode(): Int {
            var result = draft.hashCode()
            result = 31 * result + (errorMessage?.hashCode() ?: 0)
            result = 31 * result + (attachedImageBytes?.contentHashCode() ?: 0)
            return result
        }
    }
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}
