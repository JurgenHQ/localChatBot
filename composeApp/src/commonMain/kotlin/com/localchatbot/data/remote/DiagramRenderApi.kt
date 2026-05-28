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
 * Cliente para el endpoint `/generate-diagram` del Image Service (FastAPI + mermaid-cli).
 *
 * El contrato de respuesta es el MISMO que `/generate-image` (`ImageGenResponse`-like) —
 * los dos endpoints están estandarizados desde el server, así que el flujo en la app es
 * idéntico al de generación de imágenes.
 *
 * Loguea cada llamada al `NetworkInspector` con el `image_base64` truncado para no
 * saturar memoria/UI con varios MB.
 */
class DiagramRenderApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) {

    suspend fun render(baseUrl: String, request: DiagramRenderRequest): Result<DiagramRenderResponse> {
        val url = "${baseUrl.removeSuffix("/")}/generate-diagram"
        val requestJson = runCatching { json.encodeToString(DiagramRenderRequest.serializer(), request) }.getOrNull()
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
                    kind = NetworkTransaction.Kind.DiagramRender,
                    requestBody = requestJson,
                    responseStatus = response.status.value,
                    responseBody = truncateBase64InResponse(raw),
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )

            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
            }

            json.decodeFromString(DiagramRenderResponse.serializer(), raw)
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.DiagramRender,
                    requestBody = requestJson,
                    responseStatus = null,
                    responseBody = null,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = err.message
                )
            )
        }
    }

    /** Trunca strings >200 chars dentro del JSON para no volcar el base64 entero al inspector. */
    private fun truncateBase64InResponse(raw: String): String =
        Regex("\"([^\"]{200,})\"").replace(raw) { m ->
            "\"...<truncado ${m.groupValues[1].length} chars>...\""
        }
}
