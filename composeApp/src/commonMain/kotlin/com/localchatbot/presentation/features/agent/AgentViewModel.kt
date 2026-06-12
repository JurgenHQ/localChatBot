package com.localchatbot.presentation.features.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel de la pestaña Agente. Agrupa Skills, servidores MCP y el acceso al
 * sistema de archivos — todo lo que configura el comportamiento del agente.
 */
class AgentViewModel(
    private val preferences: PreferencesRepository
) : ViewModel() {

    val state: StateFlow<AppPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.Default)

    fun updateFsWorkspaceDir(value: String?) {
        viewModelScope.launch { preferences.updateFsWorkspaceDir(value) }
    }

    fun toggleFsYoloMode(value: Boolean) {
        viewModelScope.launch { preferences.updateFsYoloMode(value) }
    }

    fun toggleFsAllowOutsideWorkspace(value: Boolean) {
        viewModelScope.launch { preferences.updateFsAllowOutsideWorkspace(value) }
    }
}
