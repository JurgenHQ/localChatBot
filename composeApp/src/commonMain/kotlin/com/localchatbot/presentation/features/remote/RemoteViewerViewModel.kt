package com.localchatbot.presentation.features.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteViewerUiState(
    /** Texto del campo de URL editable. */
    val inputUrl: String = "",
    /** URL realmente cargada en el WebView. Vacía = aún no conectado. */
    val loadedUrl: String = ""
)

/**
 * Visor remoto embebido (Fase 1b): captura una URL y la carga en el WebView de la
 * plataforma. Recuerda la última URL vía [PreferencesRepository].
 */
class RemoteViewerViewModel(
    private val preferences: PreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteViewerUiState())
    val state: StateFlow<RemoteViewerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = preferences.current().remoteViewerUrl
            _state.update { it.copy(inputUrl = saved) }
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(inputUrl = value) }

    /** Normaliza, persiste y carga la URL en el WebView. */
    fun connect() {
        val url = normalize(_state.value.inputUrl)
        if (url.isBlank()) return
        _state.update { it.copy(inputUrl = url, loadedUrl = url) }
        viewModelScope.launch { preferences.updateRemoteViewerUrl(url) }
    }

    /** Descarga el WebView (vuelve al formulario) sin borrar la URL guardada. */
    fun disconnect() = _state.update { it.copy(loadedUrl = "") }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
