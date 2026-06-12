package com.localchatbot.presentation.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.ListModelsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsEditorUiState(
    val editor: SettingsEditor,
    val textDraft: String = "",
    val themeDraft: ThemeMode = ThemeMode.System,
    val accentDraft: Long = 0L,
    val availableModels: List<String> = emptyList(),
    val loadingModels: Boolean = false
) {
    /** TavilyApiKey, ApiKey y SystemPrompt permiten valor vacío; el resto requiere contenido. */
    val canSaveText: Boolean
        get() = when (editor) {
            SettingsEditor.TavilyApiKey,
            SettingsEditor.ApiKey,
            SettingsEditor.Port,
            SettingsEditor.SystemPrompt,
            SettingsEditor.ImageServiceUrl -> true
            else -> textDraft.isNotBlank()
        }
}

class SettingsEditorViewModel(
    private val preferences: PreferencesRepository,
    private val editor: SettingsEditor,
    private val listModels: ListModelsUseCase? = null
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsEditorUiState(editor = editor))
    val state: StateFlow<SettingsEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = preferences.current()
            _state.update {
                it.copy(
                    textDraft = when (editor) {
                        SettingsEditor.Ip -> prefs.connection.ip
                        SettingsEditor.Port -> prefs.connection.port
                        SettingsEditor.Model -> prefs.connection.model
                        SettingsEditor.ApiKey -> prefs.connection.apiKey
                        SettingsEditor.TavilyApiKey -> prefs.tavilyApiKey
                        SettingsEditor.SystemPrompt -> prefs.defaultSystemPrompt
                        SettingsEditor.ImageServiceUrl -> prefs.imageServiceUrl
                        else -> ""
                    },
                    themeDraft = prefs.themeMode,
                    accentDraft = prefs.accentSeed
                )
            }
            if (editor == SettingsEditor.Model && listModels != null && prefs.connection.isValid()) {
                fetchModels()
            }
        }
    }

    fun onTextChange(value: String) = _state.update { it.copy(textDraft = value) }
    fun onThemeChange(mode: ThemeMode) = _state.update { it.copy(themeDraft = mode) }
    fun onAccentChange(seed: Long) = _state.update { it.copy(accentDraft = seed) }
    fun onModelSelected(name: String) = _state.update { it.copy(textDraft = name) }

    fun fetchModels() {
        val fetch = listModels ?: return
        viewModelScope.launch {
            val cfg = preferences.current().connection
            if (!cfg.isValid()) return@launch
            _state.update { it.copy(loadingModels = true) }
            val result = fetch(cfg.baseUrl())
            _state.update {
                it.copy(
                    loadingModels = false,
                    availableModels = result.getOrDefault(emptyList())
                )
            }
        }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            when (editor) {
                SettingsEditor.Ip -> preferences.updateConnection(
                    preferences.current().connection.copy(ip = s.textDraft.trim())
                )
                SettingsEditor.Port -> preferences.updateConnection(
                    preferences.current().connection.copy(port = s.textDraft.trim())
                )
                SettingsEditor.Model -> preferences.updateConnection(
                    preferences.current().connection.copy(model = s.textDraft.trim())
                )
                SettingsEditor.ApiKey -> preferences.updateConnection(
                    preferences.current().connection.copy(apiKey = s.textDraft.trim())
                )
                SettingsEditor.Theme -> preferences.updateThemeMode(s.themeDraft)
                SettingsEditor.Accent -> preferences.updateAccent(s.accentDraft)
                SettingsEditor.TavilyApiKey -> preferences.updateTavilyApiKey(s.textDraft.trim())
                SettingsEditor.SystemPrompt -> preferences.updateDefaultSystemPrompt(s.textDraft.trim())
                SettingsEditor.ImageServiceUrl -> preferences.updateImageServiceUrl(s.textDraft.trim())
            }
            onDone()
        }
    }
}
