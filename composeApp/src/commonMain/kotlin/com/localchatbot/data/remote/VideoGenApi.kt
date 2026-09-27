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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Cliente para los endpoints de video del Image Service (`/animate`, `/cartoon-video`).
 * Mismo servicio y `baseUrl` que [ImageGenApi]/[DiagramRenderApi], pero la respuesta trae
 * `video_base64`/`video_path` en vez de imagen — se modela aparte con [VideoGenResponse].
 */
class VideoGenApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) {

    suspend fun animate(baseUrl: String, request: AnimateRequest): Result<VideoGenResponse> =
        post(baseUrl, "/animate", request, AnimateRequest.serializer(), NetworkTransaction.Kind.AnimateVideo)

    suspend fun cartoonVideo(baseUrl: String, request: CartoonVideoRequest): Result<VideoGenResponse> =
        post(baseUrl, "/cartoon-video", request, CartoonVideoRequest.serializer(), NetworkTransaction.Kind.CartoonVideo)

    private suspend fun <Req> post(
        baseUrl: String,
        path: String,
        request: Req,
        requestSerializer: KSerializer<Req>,
        kind: NetworkTransaction.Kind
    ): Result<VideoGenResponse> {
        val url = "${baseUrl.removeSuffix("/")}$path"
        val requestJson = runCatching { json.encodeToString(requestSerializer, request) }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            val bodyJson = requestJson ?: throw IllegalStateException("No se pudo serializar el request")
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
            val raw = response.bodyAsText()

            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = kind,
                    requestBody = requestJson,
                    responseStatus = response.status.value,
                    responseBody = truncateBase64InResponse(raw),
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )

            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
            }

            json.decodeFromString(VideoGenResponse.serializer(), raw)
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = kind,
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
