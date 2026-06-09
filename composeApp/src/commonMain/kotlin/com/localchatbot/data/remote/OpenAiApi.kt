package com.localchatbot.data.remote

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import com.localchatbot.core.platform.PlatformCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

class OpenAiApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) {

    suspend fun chatCompletion(
        baseUrl: String,
        request: ChatCompletionRequest
    ): Result<ChatCompletionResponse> {
        val url = "$baseUrl/chat/completions"
        val finalRequest = request.copy(stream = false)
        val requestJson = runCatching { json.encodeToString(ChatCompletionRequest.serializer(), finalRequest) }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()
        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(finalRequest)
            }
            val raw = response.bodyAsText()
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.ChatCompletion,
                    requestBody = requestJson,
                    responseStatus = response.status.value,
                    responseBody = raw,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
            }
            json.decodeFromString(ChatCompletionResponse.serializer(), raw)
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.ChatCompletion,
                    requestBody = requestJson,
                    responseStatus = null,
                    responseBody = null,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = err.message
                )
            )
        }
    }

    fun streamChatCompletion(
        baseUrl: String,
        request: ChatCompletionRequest
    ): Flow<ChatCompletionChunk> = flow {
        val url = "$baseUrl/chat/completions"
        val finalRequest = request.copy(stream = true)
        val requestJson = runCatching { json.encodeToString(ChatCompletionRequest.serializer(), finalRequest) }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()
        val rawTranscript = StringBuilder()
        var responseStatus: Int? = null
        var errorMessage: String? = null
        var parseErrorCount = 0
        var firstParseError: String? = null
        try {
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                // Solo en desktop (CIO): fuerza conexión nueva por cada stream para
                // evitar reusar una conexión que LM Studio ya cerró, lo que causaría
                // EOF inmediato en llamadas rápidas (p. ej. YOLO mode sin delay de
                // aprobación humana).
                //
                // En iOS (Darwin) este header provoca `-1005 / EPIPE` durante el
                // streaming porque NSURLSession cierra la conexión más agresivamente;
                // Darwin ya detecta conexiones muertas en su propio pool, así que
                // dejamos que negocie keep-alive por defecto.
                if (PlatformCapabilities.forceCloseHttpConnection) {
                    header(HttpHeaders.Connection, "close")
                }
                setBody(finalRequest)
            }.execute { response ->
                responseStatus = response.status.value
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
                }
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    rawTranscript.append(line).append('\n')
                    if (!line.startsWith("data:")) continue
                    val payload = line.substringAfter("data:").trim()
                    if (payload == "[DONE]") return@execute
                    runCatching { json.decodeFromString<ChatCompletionChunk>(payload) }
                        .onSuccess { emit(it) }
                        .onFailure { e ->
                            // Antes esto era silencioso → si LM Studio emitía un
                            // chunk con un formato inesperado (campo no-nullable
                            // faltante, tipo distinto), TODOS los chunks del mismo
                            // formato se descartaban sin que la UI recibiera ni
                            // un token. Ahora lo dejamos visible en el inspector
                            // y, si NO se emitió nada útil, levantamos el error
                            // al final para que la UI muestre el problema.
                            parseErrorCount++
                            if (firstParseError == null) {
                                firstParseError = "${e::class.simpleName}: ${e.message} | payload: ${payload.take(500)}"
                            }
                        }
                }
            }
        } catch (t: Throwable) {
            errorMessage = t.message
            throw t
        } finally {
            if (parseErrorCount > 0) {
                rawTranscript.append("\n## PARSE_ERRORS ($parseErrorCount chunks) ##\n")
                rawTranscript.append(firstParseError ?: "")
                if (errorMessage == null) {
                    errorMessage = "Parse error en stream ($parseErrorCount chunks descartados): $firstParseError"
                }
            }
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.ChatStream,
                    requestBody = requestJson,
                    responseStatus = responseStatus,
                    responseBody = rawTranscript.toString().ifEmpty { null },
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = errorMessage
                )
            )
        }
    }

    suspend fun listModels(baseUrl: String): Result<List<String>> = runCatching {
        val response = client.get("$baseUrl/models")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}")
        }
        response.body<ModelsResponse>().data.map { it.id }
    }

    suspend fun ping(baseUrl: String): Result<Long> = runCatching {
        val start = Clock.System.now().toEpochMilliseconds()
        val response = client.get("$baseUrl/models")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}")
        }
        Clock.System.now().toEpochMilliseconds() - start
    }
}
