package com.localchatbot.presentation.features.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.data.mcp.McpToolProvider
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class McpServerStatus { Unknown, Connecting, Connected, Error }

data class McpServerUiItem(
    val config: McpServerConfig,
    val status: McpServerStatus = McpServerStatus.Unknown,
    val toolCount: Int = 0,
    val errorMessage: String? = null
)

data class McpServersUiState(
    val servers: List<McpServerUiItem> = emptyList(),
    val showEditSheet: Boolean = false,
    val editingServer: McpServerConfig? = null
)

class McpServersViewModel(
    private val preferences: PreferencesRepository,
    private val mcpToolProvider: McpToolProvider
) : ViewModel() {

    private val _serverStatuses = MutableStateFlow<Map<String, McpServerUiItem>>(emptyMap())
    private val _showEditSheet = MutableStateFlow(false)
    private val _editingServer = MutableStateFlow<McpServerConfig?>(null)

    val state: StateFlow<McpServersUiState> = combine(
        preferences.preferences,
        _serverStatuses,
        _showEditSheet,
        _editingServer
    ) { prefs, statuses, showEdit, editing ->
        McpServersUiState(
            servers = prefs.mcpServers.map { cfg ->
                statuses[cfg.id] ?: McpServerUiItem(config = cfg)
            },
            showEditSheet = showEdit,
            editingServer = editing
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, McpServersUiState())

    fun openAddSheet() {
        _editingServer.value = null
        _showEditSheet.value = true
    }

    fun openEditSheet(server: McpServerConfig) {
        _editingServer.value = server
        _showEditSheet.value = true
    }

    fun closeSheet() {
        _showEditSheet.value = false
        _editingServer.value = null
    }

    fun saveServer(config: McpServerConfig) = viewModelScope.launch {
        val current = preferences.current().mcpServers.toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        preferences.setMcpServers(current)
        closeSheet()
    }

    fun deleteServer(serverId: String) = viewModelScope.launch {
        val updated = preferences.current().mcpServers.filter { it.id != serverId }
        preferences.setMcpServers(updated)
        _serverStatuses.update { it - serverId }
    }

    fun toggleServer(serverId: String, enabled: Boolean) = viewModelScope.launch {
        val updated = preferences.current().mcpServers.map { s ->
            if (s.id == serverId) s.copy(enabled = enabled) else s
        }
        preferences.setMcpServers(updated)
    }

    fun testConnection(serverId: String) = viewModelScope.launch {
        val config = preferences.current().mcpServers.firstOrNull { it.id == serverId } ?: return@launch
        _serverStatuses.update { it + (serverId to McpServerUiItem(config = config, status = McpServerStatus.Connecting)) }
        mcpToolProvider.testConnection(serverId)
            .onSuccess { count ->
                _serverStatuses.update {
                    it + (serverId to McpServerUiItem(config = config, status = McpServerStatus.Connected, toolCount = count))
                }
            }
            .onFailure { err ->
                _serverStatuses.update {
                    it + (serverId to McpServerUiItem(
                        config = config,
                        status = McpServerStatus.Error,
                        errorMessage = err.message?.take(80)
                    ))
                }
            }
    }
}
