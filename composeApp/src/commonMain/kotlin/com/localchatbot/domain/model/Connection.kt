package com.localchatbot.domain.model

enum class ConnectionMode { LocalNetwork, DirectUrl }

data class ConnectionConfig(
    val mode: ConnectionMode = ConnectionMode.LocalNetwork,
    val ip: String = "",
    val port: String = "1234",
    val model: String = "",
    /**
     * URL completa hacia el servidor cuando el modo es [ConnectionMode.DirectUrl].
     * Ejemplos: "https://abc.trycloudflare.com", "https://mi-tunnel.ngrok.io"
     * No incluir "/v1" — se añade automáticamente en [baseUrl].
     */
    val directUrl: String = ""
) {
    fun baseUrl(): String = when (mode) {
        ConnectionMode.LocalNetwork -> "http://$ip:$port/v1"
        ConnectionMode.DirectUrl   -> directUrl.trimEnd('/').removeSuffix("/v1") + "/v1"
    }

    fun isValid(): Boolean = when (mode) {
        ConnectionMode.LocalNetwork -> ip.isNotBlank() && port.isNotBlank() && model.isNotBlank()
        ConnectionMode.DirectUrl    -> directUrl.isNotBlank() && model.isNotBlank()
    }
}

sealed interface ConnectionStatus {
    data object Unknown : ConnectionStatus
    data object Checking : ConnectionStatus
    data class Connected(val latencyMs: Long) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}
