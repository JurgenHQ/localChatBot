package com.localchatbot.data.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Construye el JSON de una notificación JSON-RPC 2.0 (sin `id`, sin respuesta).
 * Se arma a mano porque [JsonRpcRequest] siempre lleva `id` — una notificación
 * con `id` es técnicamente un request y rompería el handshake.
 */
fun buildJsonRpcNotification(method: String, params: JsonObject?): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }

// ─── JSON-RPC 2.0 ────────────────────────────────────────────────────────────

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// ─── MCP protocol types ───────────────────────────────────────────────────────

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolInfo> = emptyList()
)

@Serializable
data class McpCallToolParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContent> = emptyList(),
    @SerialName("isError") val isError: Boolean = false
)

@Serializable
sealed class McpContent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : McpContent()

    @Serializable
    @SerialName("image")
    data class Image(val data: String, @SerialName("mimeType") val mimeType: String) : McpContent()
}
