package com.localchatbot.data.remote

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Cliente para el **Image Service** (FastAPI + ComfyUI). No es OpenAI-compatible —
 * vive aparte porque es un servicio propio. La URL base se inyecta en cada llamada
 * porque depende de configuración del usuario (suele ser la misma máquina que
 * aloja LM Studio pero en otro puerto).
 */
class ImageGenApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) {

    suspend fun generate(baseUrl: String, request: ImageGenRequest): Result<ImageGenResponse> {
        val url = "${baseUrl.removeSuffix("/")}/generate-image"
        val requestJson = runCatching { json.encodeToString(ImageGenRequest.serializer(), request) }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val raw = response.bodyAsText()

            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.ImageGen,
                    requestBody = requestJson,
                    responseStatus = response.status.value,
                    responseBody = truncateBase64InResponse(raw),
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )

            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
            }

            json.decodeFromString(ImageGenResponse.serializer(), raw)
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.ImageGen,
                    requestBody = requestJson,
                    responseStatus = null,
                    responseBody = null,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = err.message
                )
            )
        }
    }

    private fun truncateBase64InResponse(raw: String): String =
        Regex("\"([^\"]{200,})\"").replace(raw) { m ->
            "\"...<truncado ${m.groupValues[1].length} chars>...\""
        }
}
