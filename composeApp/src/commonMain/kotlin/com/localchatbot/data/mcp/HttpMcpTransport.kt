package com.localchatbot.data.mcp

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class HttpMcpTransport(
    private val url: String,
    private val extraHeaders: Map<String, String>,
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) : McpTransportLayer {

    private var nextId = 1

    override suspend fun sendRequest(method: String, params: JsonObject?): Result<String> {
        val id = JsonPrimitive(nextId++)
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val requestJson = runCatching { json.encodeToString(JsonRpcRequest.serializer(), request) }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                headers {
                    extraHeaders.forEach { (k, v) -> append(k, v) }
                }
                setBody(requestJson ?: "{}")
            }
            val raw = response.bodyAsText()
            val duration = Clock.System.now().toEpochMilliseconds() - start

            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.McpCall,
                    requestBody = requestJson,
                    responseStatus = response.status.value,
                    responseBody = raw.take(2000),
                    durationMs = duration,
                    error = if (response.status.isSuccess()) null else "HTTP ${response.status.value}"
                )
            )

            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value}: $raw")
            }

            val rpcResponse = json.decodeFromString(JsonRpcResponse.serializer(), raw)
            if (rpcResponse.error != null) {
                error("MCP error ${rpcResponse.error.code}: ${rpcResponse.error.message}")
            }
            raw
        }
    }

    override suspend fun close() = Unit
}
