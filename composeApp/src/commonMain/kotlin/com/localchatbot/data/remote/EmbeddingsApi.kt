package com.localchatbot.data.remote

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbeddingsRequest(
    val model: String,
    val input: List<String>
)

@Serializable
data class EmbeddingData(
    val embedding: List<Float>,
    val index: Int = 0
)

@Serializable
data class EmbeddingsResponse(
    val data: List<EmbeddingData> = emptyList(),
    val model: String = "",
    @SerialName("object") val objectType: String = ""
)

/**
 * Cliente de `/v1/embeddings` del mismo endpoint OpenAI-compatible que sirve el chat
 * (LM Studio lo expone; llama.cpp y Ollama también, con el modelo adecuado cargado).
 *
 * Lo usa el índice semántico del workspace ([com.localchatbot.core.index.SemanticIndex]).
 * No hay reintentos: un fallo acá degrada la tool con un mensaje claro en vez de bloquear
 * el turno — típicamente el modelo de embeddings no está cargado, y reintentar no lo carga.
 */
class EmbeddingsApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null,
    private val authTokenProvider: suspend () -> String? = { null }
) {

    /**
     * Devuelve un vector por cada entrada de [inputs], en el mismo orden.
     *
     * El orden lo garantiza el campo `index` de la respuesta, no el orden de llegada: la
     * spec de OpenAI no promete que `data` venga ordenado, y una permutación silenciosa
     * asociaría cada embedding al trozo equivocado — un bug que no se ve, solo empeora
     * los resultados.
     */
    suspend fun embed(
        baseUrl: String,
        model: String,
        inputs: List<String>
    ): Result<List<FloatArray>> {
        if (inputs.isEmpty()) return Result.success(emptyList())
        val url = "$baseUrl/embeddings"
        val request = EmbeddingsRequest(model = model, input = inputs)
        val start = Clock.System.now().toEpochMilliseconds()
        val token = authTokenProvider()
        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(request)
            }
            val raw = response.bodyAsText()
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.Embeddings,
                    // El body lleva el texto completo de N trozos; en el inspector solo
                    // interesa el modelo y cuántos fueron.
                    requestBody = "{\"model\":\"$model\",\"input\":[${inputs.size} textos]}",
                    responseStatus = response.status.value,
                    responseBody = if (response.status.isSuccess()) "[${inputs.size} vectores]" else raw,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${raw.take(200)}")
            }
            val parsed = json.decodeFromString(EmbeddingsResponse.serializer(), raw)
            if (parsed.data.size != inputs.size) {
                throw IllegalStateException(
                    "El servidor devolvió ${parsed.data.size} vectores para ${inputs.size} textos"
                )
            }
            parsed.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = url,
                    kind = NetworkTransaction.Kind.Embeddings,
                    requestBody = "{\"model\":\"$model\",\"input\":[${inputs.size} textos]}",
                    responseStatus = null,
                    responseBody = null,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = err.message
                )
            )
        }
    }
}
