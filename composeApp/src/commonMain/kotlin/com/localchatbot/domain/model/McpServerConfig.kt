package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

/**
 * Configuración de un servidor MCP.
 *
 * Transporte HTTP / Streamable HTTP (default):
 * - [url]: endpoint del servidor MCP.
 * - [headers]: cabeceras extra para autenticación (ej. `Authorization: Bearer xxx`).
 *
 * Transporte stdio ([transport] = [TRANSPORT_STDIO], solo desktop): el servidor se
 * lanza como proceso local y habla JSON-RPC por stdin/stdout.
 * - [command]: ejecutable (ej. `npx`, `uvx`, o ruta absoluta).
 * - [args]: argumentos del comando.
 * - [env]: variables de entorno extra para el proceso.
 *
 * [transport] es String (no enum) para que configs guardadas con valores futuros
 * no rompan la deserialización; los defaults mantienen retrocompatibilidad con
 * las configs HTTP ya persistidas en `mcp_servers`.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val transport: String = TRANSPORT_HTTP,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
) {
    val isStdio: Boolean get() = transport == TRANSPORT_STDIO

    companion object {
        const val TRANSPORT_HTTP = "http"
        const val TRANSPORT_STDIO = "stdio"
    }
}
