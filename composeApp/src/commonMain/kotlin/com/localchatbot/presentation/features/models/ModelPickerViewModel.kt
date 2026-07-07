package com.localchatbot.presentation.features.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.domain.model.AvailableModel
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelPickerUiState(
    val loading: Boolean = true,
    /** true si el servidor soporta cargar/descargar modelos (LM Studio >= 0.4.0). */
    val canManage: Boolean = false,
    val models: List<AvailableModel> = emptyList(),
    /** Modelo seleccionado en la conexión (prefs). */
    val selectedModelId: String = "",
    /** Modelo con un load/unload en curso — su fila muestra spinner y el resto se deshabilita. */
    val busyModelId: String? = null,
    val error: String? = null,
    /** Diálogo "descargar anterior o mantener ambos" pendiente de respuesta. */
    val pendingSwap: PendingSwap? = null
)

data class PendingSwap(
    val target: AvailableModel,
    val loaded: List<AvailableModel>
)

class ModelPickerViewModel(
    private val preferences: PreferencesRepository,
    private val modelRepository: ModelRepository,
    private val chatRepository: ChatRepository,
    private val activeSessionStore: ActiveSessionStore,
    streamingStateStore: StreamingStateStore
) : ViewModel() {

    private val _state = MutableStateFlow(ModelPickerUiState())
    val state: StateFlow<ModelPickerUiState> = _state.asStateFlow()

    /** Con un stream activo cargar/descargar mataría la respuesta en curso. */
    val streamingActive: StateFlow<Boolean> = streamingStateStore.streaming
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val cfg = preferences.current().connection
            if (!cfg.isValid()) {
                _state.update {
                    it.copy(loading = false, models = emptyList(), error = "Configura la conexión en Ajustes")
                }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, selectedModelId = cfg.model) }
            val result = modelRepository.listModelsDetailed(cfg.baseUrl())
            result.fold(
                onSuccess = { catalog ->
                    _state.update {
                        it.copy(loading = false, canManage = catalog.canManage, models = catalog.models)
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(loading = false, error = err.message ?: "No se pudieron listar los modelos")
                    }
                }
            )
        }
    }

    fun onModelClick(model: AvailableModel, onDone: () -> Unit) {
        val s = _state.value
        if (s.busyModelId != null) return
        // Sin gestión, o modelo ya cargado (o estado desconocido): selección pura.
        if (!s.canManage || model.loaded != false) {
            selectModel(model.id, onDone)
            return
        }
        if (streamingActive.value) return
        val loadedNow = s.models.filter { it.loaded == true }
        if (loadedNow.isEmpty()) {
            loadAndSelect(model, unloadPrevious = false, onDone = onDone)
        } else {
            _state.update { it.copy(pendingSwap = PendingSwap(model, loadedNow)) }
        }
    }

    fun confirmSwap(unloadPrevious: Boolean, onDone: () -> Unit) {
        val swap = _state.value.pendingSwap ?: return
        _state.update { it.copy(pendingSwap = null) }
        loadAndSelect(swap.target, unloadPrevious, previouslyLoaded = swap.loaded, onDone = onDone)
    }

    fun dismissSwap() = _state.update { it.copy(pendingSwap = null) }

    fun onUnload(model: AvailableModel) {
        if (_state.value.busyModelId != null || streamingActive.value) return
        viewModelScope.launch {
            val cfg = preferences.current().connection
            _state.update { it.copy(busyModelId = model.id, error = null) }
            var firstError: String? = null
            model.instanceIds.forEach { instanceId ->
                modelRepository.unloadModel(cfg.baseUrl(), instanceId).onFailure { err ->
                    if (firstError == null) firstError = err.message
                }
            }
            _state.update { it.copy(busyModelId = null, error = firstError) }
            refresh()
        }
    }

    private fun loadAndSelect(
        model: AvailableModel,
        unloadPrevious: Boolean,
        previouslyLoaded: List<AvailableModel> = emptyList(),
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val cfg = preferences.current().connection
            _state.update { it.copy(busyModelId = model.id, error = null) }
            val loadResult = modelRepository.loadModel(cfg.baseUrl(), model.id)
            loadResult.fold(
                onSuccess = {
                    var firstError: String? = null
                    if (unloadPrevious) {
                        previouslyLoaded
                            .filter { it.id != model.id }
                            .flatMap { it.instanceIds }
                            .forEach { instanceId ->
                                modelRepository.unloadModel(cfg.baseUrl(), instanceId).onFailure { err ->
                                    if (firstError == null) firstError = "No se pudo descargar el modelo anterior: ${err.message}"
                                }
                            }
                    }
                    // Prefs se actualizan DESPUÉS de cargar: así el refetch de contexto
                    // del chat (reactivo al cambio de prefs) lee el contexto real cargado.
                    selectModelInternal(model.id)
                    _state.update { it.copy(busyModelId = null, error = firstError) }
                    if (firstError == null) onDone() else refresh()
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(busyModelId = null, error = "No se pudo cargar el modelo: ${err.message}")
                    }
                }
            )
        }
    }

    private fun selectModel(modelId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            selectModelInternal(modelId)
            onDone()
        }
    }

    private suspend fun selectModelInternal(modelId: String) {
        val cfg = preferences.current().connection
        preferences.updateConnection(cfg.copy(model = modelId))
        // Doble escritura: el subtítulo del chat muestra session.model ?: prefs.model,
        // y SendMessageUseCase escribe el modelo usado en la sesión tras cada stream —
        // sin esto la sesión activa seguiría mostrando (y usando) el modelo viejo.
        activeSessionStore.activeSessionId.value?.let { sessionId ->
            chatRepository.updateModel(sessionId, modelId)
        }
    }
}
