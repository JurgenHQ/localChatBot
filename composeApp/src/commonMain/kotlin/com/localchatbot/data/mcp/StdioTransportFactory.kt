package com.localchatbot.data.mcp

import com.localchatbot.core.debug.NetworkInspector
import kotlinx.serialization.json.Json

/**
 * Crea un transporte MCP stdio (proceso local hablando JSON-RPC newline-delimited
 * por stdin/stdout). Solo existe en desktop (necesita ProcessBuilder); en
 * Android/iOS devuelve null y el provider lo reporta como no soportado.
 */
expect fun createStdioMcpTransport(
    command: String,
    args: List<String>,
    env: Map<String, String>,
    json: Json,
    inspector: NetworkInspector? = null
): McpTransportLayer?
