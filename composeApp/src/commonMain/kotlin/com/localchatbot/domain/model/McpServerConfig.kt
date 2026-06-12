package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

/**
 * Configuración de un servidor MCP remoto (transporte HTTP / Streamable HTTP).
 *
 * - [url]: endpoint del servidor MCP.
 * - [headers]: cabeceras extra para autenticación (ej. `Authorization: Bearer xxx`).
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)
