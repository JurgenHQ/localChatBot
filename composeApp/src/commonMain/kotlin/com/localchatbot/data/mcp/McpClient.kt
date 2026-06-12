package com.localchatbot.data.mcp

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpClient(
    private val transport: McpTransportLayer,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val initTimeoutMs: Long = 10_000,
    private val callTimeoutMs: Long = 30_000
) {

    private var initialized = false

    suspend fun initialize(): Result<Unit> = runCatching {
        withTimeout(initTimeoutMs) {
            // `capabilities` es obligatorio en el spec MCP (aunque sea {}). Lo armamos
            // a mano para garantizar que siempre viaje — si se omite, servers como
            // Context7 rechazan el request con "No valid session ID provided".
            val params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "LocalChatBot")
                    put("version", "1.0.0")
                })
            }
            transport.sendRequest("initialize", params).getOrThrow()
            // Notificación sin id: el server no responde, no esperamos lectura.
            transport.sendNotification("notifications/initialized", null)
            initialized = true
        }
    }

    suspend fun listTools(): Result<List<McpToolInfo>> = runCatching {
        ensureInitialized()
        withTimeout(initTimeoutMs) {
            val raw = transport.sendRequest("tools/list", null).getOrThrow()
            val rpc = json.decodeFromString(JsonRpcResponse.serializer(), raw)
            val resultElement = rpc.result ?: error("null result from tools/list")
            json.decodeFromJsonElement(McpToolsListResult.serializer(), resultElement).tools
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject?): Result<McpCallToolResult> = runCatching {
        ensureInitialized()
        withTimeout(callTimeoutMs) {
            val params = buildJsonObject {
                put("name", name)
                if (arguments != null) put("arguments", arguments)
            }
            val raw = transport.sendRequest("tools/call", params).getOrThrow()
            val rpc = json.decodeFromString(JsonRpcResponse.serializer(), raw)
            val resultElement = rpc.result ?: error("null result from tools/call")
            json.decodeFromJsonElement(McpCallToolResult.serializer(), resultElement)
        }
    }

    suspend fun close() = transport.close()

    private suspend fun ensureInitialized() {
        if (!initialized) initialize().getOrThrow()
    }
}
