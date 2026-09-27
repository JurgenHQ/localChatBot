package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionConfig(
    /**
     * Host o IP del servidor. Puede ser una IP de LAN/VPN (`192.168.1.42`) o un
     * dominio (`api.openai.com`, `abc.ngrok.io`). Si pegas una URL con esquema,
     * [baseUrl] lo limpia.
     */
    val ip: String = "",
    /** Puerto opcional. Vacío = sin puerto explícito (útil para cloud en 443/80). */
    val port: String = "1234",
    /** Si está activo, el endpoint se contacta por HTTPS en vez de HTTP. */
    val useHttps: Boolean = false,
    val model: String = "",
    /**
     * API key opcional para autenticar contra el endpoint del modelo. Se envía como
     * header `Authorization: Bearer <apiKey>`. Útil para LM Studio con autenticación
     * activada o para proveedores cloud (OpenAI, DeepSeek, etc.). Vacío = sin header.
     */
    val apiKey: String = ""
) {
    fun baseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        val host = ip.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .removeSuffix("/v1")
        val portPart = if (port.isBlank()) "" else ":${port.trim()}"
        return "$scheme://$host$portPart/v1"
    }

    fun isValid(): Boolean = ip.isNotBlank() && model.isNotBlank()
}

/**
 * Perfil de conexión nombrado (p. ej. "IA local", "OpenAI"). El usuario puede tener hasta 3
 * y elegir cuál está activo; ver [AppPreferences.connection].
 */
@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val config: ConnectionConfig = ConnectionConfig()
)

sealed interface ConnectionStatus {
    data object Unknown : ConnectionStatus
    data object Checking : ConnectionStatus
    data class Connected(val latencyMs: Long) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}
