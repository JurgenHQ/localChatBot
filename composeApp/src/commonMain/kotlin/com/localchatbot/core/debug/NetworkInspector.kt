package com.localchatbot.core.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Pequeño store en memoria con las últimas N llamadas HTTP a la API del modelo.
 * Pensado como herramienta de debug para devs: ver el JSON crudo enviado/recibido,
 * duración, finish_reason, errores, etc. No persiste — se reinicia con la app.
 */
class NetworkInspector(private val capacity: Int = 50) {

    private val _entries = MutableStateFlow<List<NetworkTransaction>>(emptyList())
    val entries: StateFlow<List<NetworkTransaction>> = _entries.asStateFlow()

    fun record(transaction: NetworkTransaction) {
        // Se sanea ANTES de guardar: el inspector está siempre activo y cada ChatStream
        // traía el historial entero (con imágenes en base64) más la transcripción SSE
        // completa. 50 de esas en una tarea de agente larga eran cientos de MB de heap
        // retenidos solo por si alguien abría esta pantalla.
        val sanitized = transaction.copy(
            requestBody = transaction.requestBody?.let(::sanitizeBody),
            responseBody = transaction.responseBody?.let(::sanitizeBody)
        )
        _entries.update { current ->
            val next = listOf(sanitized) + current
            if (next.size > capacity) next.take(capacity) else next
        }
    }

    fun clear() = _entries.update { emptyList() }

    fun newId(): String =
        Clock.System.now().toEpochMilliseconds().toString(36) +
            "-" + Random.nextInt(0, 1_000_000).toString(36)

    companion object {
        /** Tope por cuerpo guardado. Suficiente para depurar un request; no para el historial entero. */
        const val MAX_BODY_CHARS = 64_000

        /** Del tope, cuánto se conserva del principio; el resto sale del final. */
        private const val HEAD_CHARS = 48_000

        /** Data URLs con base64 largo (imágenes/vídeos adjuntos o generados). */
        private val DATA_URL_REGEX = Regex("""data:([\w.+/-]+);base64,[A-Za-z0-9+/=\\]{64,}""")

        /**
         * Quita el base64 de las data URLs (se deja el tipo y cuántos caracteres había) y
         * recorta a [MAX_BODY_CHARS] quedándose con cabeza y cola: en un request lo útil
         * suele estar al principio (modelo, system) y al final (últimos mensajes, tools),
         * y en una transcripción SSE el final trae `finish_reason` y `usage`.
         */
        fun sanitizeBody(body: String): String {
            val noBase64 = if (body.contains(";base64,")) {
                DATA_URL_REGEX.replace(body) { m ->
                    "data:${m.groupValues[1]};base64,<${m.value.length} chars omitidos>"
                }
            } else body
            if (noBase64.length <= MAX_BODY_CHARS) return noBase64
            val tailChars = MAX_BODY_CHARS - HEAD_CHARS
            val omitted = noBase64.length - MAX_BODY_CHARS
            return noBase64.take(HEAD_CHARS) +
                "\n\n…<$omitted caracteres omitidos por el inspector>…\n\n" +
                noBase64.takeLast(tailChars)
        }
    }
}

/**
 * Una llamada HTTP completa. Para streaming, `responseBody` contiene la
 * concatenación cruda de los chunks SSE recibidos.
 */
data class NetworkTransaction(
    val id: String,
    val timestampEpochMs: Long,
    val method: String,
    val url: String,
    val kind: Kind,
    val requestBody: String?,
    val responseStatus: Int?,
    val responseBody: String?,
    val durationMs: Long,
    val error: String? = null
) {
    enum class Kind {
        ChatCompletion, ChatStream, ListModels, Ping, ImageGen, DiagramRender, WebSearch, McpCall,
        TextImageGen, Cartoon, AnimateVideo, CartoonVideo, ModelLoad, ModelUnload, WebFetch,
        Embeddings
    }

    val isError: Boolean get() = error != null || (responseStatus != null && responseStatus >= 400)
}
