package com.localchatbot.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Cliente para el **Image Service** (FastAPI + ComfyUI). No es OpenAI-compatible —
 * vive aparte porque es un servicio propio. La URL base se inyecta en cada llamada
 * porque depende de configuración del usuario (suele ser la misma máquina que
 * aloja LM Studio pero en otro puerto).
 */
class ImageGenApi(private val client: HttpClient) {

    suspend fun generate(baseUrl: String, request: ImageGenRequest): Result<ImageGenResponse> = runCatching {
        val url = "${baseUrl.removeSuffix("/")}/generate-image"
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
        }
        response.body<ImageGenResponse>()
    }
}
