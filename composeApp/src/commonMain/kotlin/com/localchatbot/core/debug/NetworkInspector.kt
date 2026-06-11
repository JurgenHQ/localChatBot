package com.localchatbot.core.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Pequeño store en memoria con las últimas N llamadas HTTP a la API del modelo.
 * Pensado como herramienta de debug para devs: ver el JSON crudo enviado/recibido,
 * duración, finish_reason, errores, etc. No persiste — se reinicia con la app.
 */
class NetworkInspector(private val capacity: Int = 50) {

    private val _entries = MutableStateFlow<List<NetworkTransaction>>(emptyList())
    val entries: StateFlow<List<NetworkTransaction>> = _entries.asStateFlow()

    fun record(transaction: NetworkTransaction) {
        _entries.update { current ->
            val next = listOf(transaction) + current
            if (next.size > capacity) next.take(capacity) else next
        }
    }

    fun clear() = _entries.update { emptyList() }

    fun newId(): String =
        Clock.System.now().toEpochMilliseconds().toString(36) +
            "-" + Random.nextInt(0, 1_000_000).toString(36)
}

/**
 * Una llamada HTTP completa. Para streaming, `responseBody` contiene la
 * concatenación cruda de los chunks SSE recibidos.
 */
data class NetworkTransaction(
    val id: String,
    val timestampEpochMs: Long,
    val method: String,
    val url: String,
    val kind: Kind,
    val requestBody: String?,
    val responseStatus: Int?,
    val responseBody: String?,
    val durationMs: Long,
    val error: String? = null
) {
    enum class Kind { ChatCompletion, ChatStream, ListModels, Ping, ImageGen, DiagramRender, WebSearch, McpCall }

    val isError: Boolean get() = error != null || (responseStatus != null && responseStatus >= 400)
}
