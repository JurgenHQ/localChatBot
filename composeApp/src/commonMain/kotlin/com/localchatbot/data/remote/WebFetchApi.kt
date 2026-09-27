package com.localchatbot.data.remote

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock

/** Contenido descargado de una URL, ya como texto. */
data class FetchedPage(
    val url: String,
    val status: Int,
    val contentType: String?,
    val body: String
)

/**
 * Descarga una URL para que el agente pueda **leer** una página, no solo buscarla.
 *
 * Complementa a [TavilyApi]: la búsqueda devuelve fragmentos, esto devuelve el documento.
 */
class WebFetchApi(
    private val client: HttpClient,
    private val inspector: NetworkInspector? = null
) {

    suspend fun fetch(url: String): Result<FetchedPage> {
        val start = Clock.System.now().toEpochMilliseconds()
        return runCatching {
            val response = client.get(url) {
                // Sin User-Agent, bastantes sitios responden 403 al cliente por defecto de
                // Ktor. Se declara uno de navegador, que es lo que esperan.
                header(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (compatible; LocalChatBot/1.0; +https://github.com/localchatbot)"
                )
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/json;q=0.9,text/plain;q=0.8,*/*;q=0.5")
            }
            val raw = response.bodyAsText()
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "GET",
                    url = url,
                    kind = NetworkTransaction.Kind.WebFetch,
                    requestBody = null,
                    responseStatus = response.status.value,
                    // El cuerpo entero de una página infla el inspector sin aportar nada.
                    responseBody = raw.take(2_000),
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value} ${response.status.description}")
            }
            FetchedPage(
                url = url,
                status = response.status.value,
                contentType = response.headers[HttpHeaders.ContentType],
                body = raw
            )
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "GET",
                    url = url,
                    kind = NetworkTransaction.Kind.WebFetch,
                    requestBody = null,
                    responseStatus = null,
                    responseBody = null,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = err.message
                )
            )
        }
    }
}
