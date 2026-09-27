package com.localchatbot.data.mcp

import com.localchatbot.core.debug.NetworkInspector
import kotlinx.serialization.json.Json

actual fun createStdioMcpTransport(
    command: String,
    args: List<String>,
    env: Map<String, String>,
    json: Json,
    inspector: NetworkInspector?
): McpTransportLayer? = null
