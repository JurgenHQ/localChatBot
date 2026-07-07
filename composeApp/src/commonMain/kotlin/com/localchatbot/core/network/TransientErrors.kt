package com.localchatbot.core.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException

/**
 * Decide si un error de red merece reintento. Primero por tipo de excepción
 * Ktor (fiable); como fallback, heurística sobre el mensaje para excepciones
 * que las plataformas envuelven en IOException genéricas (p. ej.
 * "Connection refused" del engine CIO/OkHttp/Darwin).
 *
 * Vive en core (no en domain) para que la capa domain no importe Ktor.
 */
fun isTransientNetworkError(e: Throwable): Boolean = when (e) {
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException -> true
    is ServerResponseException -> e.response.status.value in TRANSIENT_HTTP_CODES
    else -> {
        val msg = e.message?.lowercase() ?: ""
        msg.contains("timeout") ||
            msg.contains("reset by peer") ||
            msg.contains("connection refused") ||
            // Suspensión en iOS: al pasar la app a background NSURLSession mata el
            // socket y Darwin reporta NSURLErrorNetworkConnectionLost (-1005,
            // "The network connection was lost.") o errno 53 ("Software caused
            // connection abort"). Reintentable: el retry ya corre en foreground.
            msg.contains("connection was lost") ||
            msg.contains("connection abort") ||
            msg.contains("socket is not connected") ||
            msg.contains("socket closed") ||
            TRANSIENT_HTTP_CODES.any { msg.contains(it.toString()) }
    }
}

private val TRANSIENT_HTTP_CODES = setOf(502, 503, 504)

/**
 * Mensaje amigable para el usuario cuando el stream falla definitivamente
 * (agotados los reintentos y reanudaciones). Evita mostrar textos crudos del
 * engine como "NSURLErrorDomain -1005".
 */
fun friendlyStreamErrorMessage(e: Throwable): String =
    if (isTransientNetworkError(e))
        "Se perdió la conexión con el servidor y no se pudo reanudar. " +
            "Comprueba que el servidor del modelo siga activo y reintenta."
    else e.message ?: "Error inesperado durante la respuesta"
