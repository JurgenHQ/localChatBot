package com.localchatbot.presentation.features.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.ConnectionMode
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<ChatSession> = emptyList(),
    val query: String = "",
    val drawerOpen: Boolean = false,
    val connectionLabel: String = ""
) {
    val filtered: List<ChatSession>
        get() = if (query.isBlank()) sessions
        else sessions.filter { it.title.contains(query, ignoreCase = true) }
}

class SessionsViewModel(
    private val chatRepository: ChatRepository,
    preferences: PreferencesRepository,
    private val activeSessionStore: ActiveSessionStore,
    private val createSessionUseCase: CreateSessionUseCase
) : ViewModel() {

    private val _local = MutableStateFlow(LocalState())

    val state: StateFlow<SessionsUiState> = combine(
        chatRepository.sessions,
        preferences.preferences,
        _local
    ) { sessions, prefs, local ->
        SessionsUiState(
            sessions = sessions,
            query = local.query,
            drawerOpen = local.drawerOpen,
            connectionLabel = when {
                !prefs.connection.isValid() -> prefs.connection.model
                prefs.connection.mode == ConnectionMode.DirectUrl ->
                    prefs.connection.directUrl
                        .removePrefix("https://").removePrefix("http://")
                        .trimEnd('/')
                        .take(32)
                else -> "${prefs.connection.ip}:${prefs.connection.port}"
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    fun openDrawer() = _local.update { it.copy(drawerOpen = true) }
    fun closeDrawer() = _local.update { it.copy(drawerOpen = false) }
    fun onQueryChange(value: String) = _local.update { it.copy(query = value) }

    fun selectSession(id: String) {
        activeSessionStore.set(id)
        closeDrawer()
    }

    fun newSession() {
        viewModelScope.launch {
            val session = createSessionUseCase()
            activeSessionStore.set(session.id)
            closeDrawer()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            activeSessionStore.clearIfMatches(id)
        }
    }

    fun renameSession(id: String, newTitle: String) {
        val cleaned = newTitle.trim().ifBlank { return }
        viewModelScope.launch { chatRepository.updateTitle(id, cleaned) }
    }

    fun togglePinned(id: String) {
        val session = state.value.sessions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { chatRepository.setPinned(id, !session.pinned) }
    }

    private data class LocalState(
        val query: String = "",
        val drawerOpen: Boolean = false
    )
}
