package com.localchatbot.presentation.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CheckConnectionUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SettingsEditor {
    data object Ip : SettingsEditor
    data object Port : SettingsEditor
    data object Model : SettingsEditor
    data object ApiKey : SettingsEditor
    data object Theme : SettingsEditor
    data object Accent : SettingsEditor
    data object TavilyApiKey : SettingsEditor
    data object SystemPrompt : SettingsEditor
    data object ImageServiceUrl : SettingsEditor
}

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences.Default,
    val status: ConnectionStatus = ConnectionStatus.Unknown,
    val openEditor: SettingsEditor? = null,
    /** JSON pendiente de importar; cuando es != null se muestra el diálogo de confirmación. */
    val pendingImportJson: String? = null,
    /** Mensaje efímero para snackbar (éxito o error de export/import). */
    val message: String? = null
)

class SettingsViewModel(
    private val preferences: PreferencesRepository,
    private val chats: ChatRepository,
    private val checkConnection: CheckConnectionUseCase
) : ViewModel() {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    private val _openEditor = MutableStateFlow<SettingsEditor?>(null)
    private val _pendingImportJson = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)

    /** Emite el JSON exportado para que la pantalla lo pase al file launcher. */
    private val _exportEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val exportEvents: SharedFlow<String> = _exportEvents

    val state: StateFlow<SettingsUiState> = combine(
        preferences.preferences,
        _status,
        _openEditor,
        _pendingImportJson,
        _message
    ) { prefs, status, editor, pendingImport, message ->
        SettingsUiState(
            preferences = prefs,
            status = status,
            openEditor = editor,
            pendingImportJson = pendingImport,
            message = message
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        viewModelScope.launch {
            preferences.preferences.collect { prefs -> refreshStatus(prefs.connection) }
        }
    }

    fun open(editor: SettingsEditor) = _openEditor.update { editor }
    fun closeEditor() = _openEditor.update { null }

    fun toggleHttps(value: Boolean) {
        viewModelScope.launch {
            preferences.updateConnection(preferences.current().connection.copy(useHttps = value))
        }
    }

    fun retryConnection() {
        viewModelScope.launch { refreshStatus(preferences.current().connection) }
    }

    fun clearHistory() = viewModelScope.launch { chats.clearAll() }

    /** Construye el JSON y lo emite a la pantalla (que abre el diálogo "guardar como"). */
    fun exportSettings() {
        viewModelScope.launch {
            runCatching { preferences.exportJson() }
                .onSuccess { _exportEvents.emit(it) }
                .onFailure { _message.value = "Error al exportar: ${it.message}" }
        }
    }

    /** La pantalla llama esto tras leer el archivo elegido; arma el diálogo de confirmación. */
    fun onImportFileSelected(json: String) {
        _pendingImportJson.value = json
    }

    fun confirmImport() {
        val json = _pendingImportJson.value ?: return
        viewModelScope.launch {
            runCatching { preferences.importJson(json) }
                .onSuccess { _message.value = "Configuración importada correctamente" }
                .onFailure { _message.value = "Error al importar: ${it.message}" }
            _pendingImportJson.value = null
        }
    }

    fun dismissImport() {
        _pendingImportJson.value = null
    }

    fun fileError(msg: String) {
        _message.value = msg
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun refreshStatus(cfg: ConnectionConfig) {
        if (!cfg.isValid()) {
            _status.value = ConnectionStatus.Unknown
            return
        }
        _status.value = ConnectionStatus.Checking
        val result = checkConnection(cfg.baseUrl())
        _status.value = result.fold(
            onSuccess = { ConnectionStatus.Connected(it) },
            onFailure = { e -> ConnectionStatus.Error(e.message ?: "Sin conexión") }
        )
    }
}
