package com.localchatbot.data.mcp

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Transporte HTTP para MCP siguiendo el spec **Streamable HTTP**:
 *
 * - Manda `Accept: application/json, text/event-stream` (el server puede contestar
 *   con JSON plano o con un stream SSE).
 * - Captura el `Mcp-Session-Id` que el server asigna en `initialize` y lo reenvía
 *   en cada llamada siguiente.
 * - Parsea las respuestas SSE (`text/event-stream`) extrayendo el mensaje JSON-RPC.
 */
class HttpMcpTransport(
    private val url: String,
    private val extraHeaders: Map<String, String>,
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) : McpTransportLayer {

    private var nextId = 1
    private var sessionId: String? = null

    override suspend fun sendRequest(method: String, params: JsonObject?): Result<String> {
        val id = JsonPrimitive(nextId++)
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val requestJson = runCatching { json.encodeToString(JsonRpcRequest.serializer(), request) }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "application/json, text/event-stream")
                    // Reenvía el session id que el server asignó en initialize.
                    sessionId?.let { append(MCP_SESSION_HEADER, it) }
                    extraHeaders.forEach { (k, v) -> append(k, v) }
                }
                setBody(requestJson ?: "{}")
            }

            // El server asigna el session id en la respuesta de initialize; lo guardamos
            // para reenviarlo en todas las llamadas posteriores de esta conexión.
            response.headers[MCP_SESSION_HEADER]?.let { sessionId = it }

            val rawBody = response.bodyAsText()
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
                    responseBody = rawBody.take(2000),
                    durationMs = duration,
                    error = if (response.status.isSuccess()) null else "HTTP ${response.status.value}"
                )
            )

            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value}: $rawBody")
            }

            // Notificaciones (notifications/*) y respuestas 202 Accepted no traen cuerpo.
            if (rawBody.isBlank()) return@runCatching ""

            // Streamable HTTP responde con SSE (text/event-stream) o JSON plano.
            val payload = if (response.isEventStream()) {
                extractJsonRpcFromSse(rawBody, id)
                    ?: error("Sin mensaje JSON-RPC en el stream SSE")
            } else {
                rawBody
            }

            val rpcResponse = json.decodeFromString(JsonRpcResponse.serializer(), payload)
            if (rpcResponse.error != null) {
                error("MCP error ${rpcResponse.error.code}: ${rpcResponse.error.message}")
            }
            payload
        }
    }

    override suspend fun sendNotification(method: String, params: JsonObject?): Result<Unit> = runCatching {
        val body = json.encodeToString(JsonObject.serializer(), buildJsonRpcNotification(method, params))
        client.post(url) {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Accept, "application/json, text/event-stream")
                sessionId?.let { append(MCP_SESSION_HEADER, it) }
                extraHeaders.forEach { (k, v) -> append(k, v) }
            }
            setBody(body)
        }
        // Las notificaciones devuelven 202 Accepted sin cuerpo JSON-RPC: no parseamos.
    }

    override suspend fun close() {
        sessionId = null
    }

    private fun HttpResponse.isEventStream(): Boolean =
        (headers[HttpHeaders.ContentType] ?: "").contains("text/event-stream", ignoreCase = true)

    /**
     * Extrae el mensaje JSON-RPC de un stream SSE. Cada evento agrupa una o más
     * líneas `data:` (que el spec SSE une con `\n`) y se separa del siguiente por
     * una línea en blanco. Devolvemos el primer bloque cuyo `id` coincide con el
     * request; si ninguno coincide, el primer bloque que parsee como JSON-RPC.
     */
    private fun extractJsonRpcFromSse(body: String, expectedId: JsonPrimitive): String? {
        val dataBlocks = mutableListOf<String>()
        val currentLines = mutableListOf<String>()

        fun flush() {
            if (currentLines.isNotEmpty()) {
                dataBlocks.add(currentLines.joinToString("\n"))
                currentLines.clear()
            }
        }

        for (line in body.lineSequence()) {
            when {
                line.startsWith("data:") -> currentLines.add(line.removePrefix("data:").removePrefix(" "))
                line.isBlank() -> flush()
            }
        }
        flush()

        val matching = dataBlocks.firstOrNull { block ->
            runCatching {
                json.decodeFromString(JsonRpcResponse.serializer(), block).id == expectedId
            }.getOrDefault(false)
        }
        if (matching != null) return matching

        return dataBlocks.firstOrNull { block ->
            runCatching { json.decodeFromString(JsonRpcResponse.serializer(), block) }.isSuccess
        }
    }

    companion object {
        private const val MCP_SESSION_HEADER = "Mcp-Session-Id"
    }
}
