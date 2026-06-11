package com.localchatbot.data.mcp

import kotlinx.serialization.json.JsonObject

interface McpTransportLayer {
    suspend fun sendRequest(method: String, params: JsonObject?): Result<String>
    suspend fun close()
}
