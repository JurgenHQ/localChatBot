package com.localchatbot.data.mcp

expect fun createStdioTransport(
    command: String,
    args: List<String>,
    env: Map<String, String>
): McpTransportLayer?
