package com.localchatbot.presentation.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CheckConnectionUseCase
import com.localchatbot.domain.usecase.ListModelsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    val ip: String = "",
    val port: String = "1234",
    val useHttps: Boolean = false,
    val model: String = "",
    val status: ConnectionStatus = ConnectionStatus.Unknown,
    val availableModels: List<String> = emptyList(),
    val loadingModels: Boolean = false
) {
    val canSubmit: Boolean get() = model.isNotBlank() && status is ConnectionStatus.Connected
    fun toConfig() = ConnectionConfig(
        ip = ip.trim(),
        port = port.trim(),
        useHttps = useHttps,
        model = model.trim()
    )
}

class OnboardingViewModel(
    private val preferences: PreferencesRepository,
    private val checkConnection: CheckConnectionUseCase,
    private val listModels: ListModelsUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = preferences.current().connection
            _state.update {
                it.copy(
                    ip = cfg.ip,
                    port = cfg.port,
                    useHttps = cfg.useHttps,
                    model = cfg.model
                )
            }
        }
    }

    fun onIpChange(v: String) = _state.update {
        it.copy(ip = v, status = ConnectionStatus.Unknown, availableModels = emptyList())
    }
    fun onPortChange(v: String) = _state.update {
        it.copy(port = v.filter { c -> c.isDigit() }, status = ConnectionStatus.Unknown, availableModels = emptyList())
    }
    fun onHttpsChange(v: Boolean) = _state.update {
        it.copy(useHttps = v, status = ConnectionStatus.Unknown, availableModels = emptyList())
    }
    fun onModelChange(v: String) = _state.update { it.copy(model = v, status = ConnectionStatus.Unknown) }
    fun onModelSelected(name: String) = _state.update { it.copy(model = name) }

    fun testConnection() {
        val cfg = _state.value.toConfig()
        if (!cfg.isValid()) return
        viewModelScope.launch {
            _state.update { it.copy(status = ConnectionStatus.Checking) }
            val result = checkConnection(cfg.baseUrl())
            _state.update {
                it.copy(
                    status = result.fold(
                        onSuccess = { ms -> ConnectionStatus.Connected(ms) },
                        onFailure = { e -> ConnectionStatus.Error(e.message ?: "Error de conexión") }
                    )
                )
            }
            if (result.isSuccess) fetchModels()
        }
    }

    fun fetchModels() {
        val cfg = _state.value.toConfig()
        if (!cfg.isValid()) return
        viewModelScope.launch {
            _state.update { it.copy(loadingModels = true) }
            val result = listModels(cfg.baseUrl())
            _state.update {
                it.copy(
                    loadingModels = false,
                    availableModels = result.getOrDefault(emptyList())
                )
            }
        }
    }

    fun finish(onDone: () -> Unit) {
        val cfg = _state.value.toConfig()
        if (!cfg.isValid()) return
        viewModelScope.launch {
            preferences.updateConnection(cfg)
            preferences.markOnboardingDone()
            onDone()
        }
    }
}
