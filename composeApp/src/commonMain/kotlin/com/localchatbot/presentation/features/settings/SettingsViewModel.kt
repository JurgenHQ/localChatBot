package com.localchatbot.presentation.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CheckConnectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val openEditor: SettingsEditor? = null
)

class SettingsViewModel(
    private val preferences: PreferencesRepository,
    private val chats: ChatRepository,
    private val checkConnection: CheckConnectionUseCase
) : ViewModel() {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    private val _openEditor = MutableStateFlow<SettingsEditor?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        preferences.preferences,
        _status,
        _openEditor
    ) { prefs, status, editor ->
        SettingsUiState(preferences = prefs, status = status, openEditor = editor)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    val openEditor: StateFlow<SettingsEditor?> = _openEditor.asStateFlow()

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
