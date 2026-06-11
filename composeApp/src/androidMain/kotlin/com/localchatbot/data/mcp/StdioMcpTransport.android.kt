package com.localchatbot.data.mcp

actual fun createStdioTransport(
    command: String,
    args: List<String>,
    env: Map<String, String>
): McpTransportLayer? = null
