package com.localchatbot.data.remote

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Cliente para la API extendida de **LM Studio** (`/api/v0/...` y `/api/v1/...`). No forma
 * parte del estándar OpenAI: la usamos cuando el servidor la expone para enriquecer la UX
 * (longitud de contexto real, estado del modelo, carga/descarga de modelos, etc.). Si el
 * servidor es Ollama, llama.cpp o cualquier otro, las llamadas de lectura devuelven null
 * silenciosamente y seguimos funcionando con los endpoints OpenAI estándar de [OpenAiApi].
 */
class LmStudioApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null,
    /** Misma API key que [OpenAiApi]: se envía como `Authorization: Bearer` si LM Studio tiene auth activada. */
    private val authTokenProvider: suspend () -> String? = { null }
) {

    /**
     * Devuelve la longitud de contexto del modelo indicado, o null si:
     *  - el servidor no expone `/api/v0/models` (no es LM Studio),
     *  - el modelo no aparece en la lista,
     *  - el JSON no trae los campos esperados.
     */
    suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? = runCatching {
        val all = fetchAllModels(baseUrl) ?: return@runCatching null
        // Exact match first; if not found, use the first loaded model — LM Studio
        // uses whatever is loaded regardless of the model ID in the request.
        val match = all.firstOrNull { it.id == modelId }
            ?: all.firstOrNull { it.state.equals("loaded", ignoreCase = true) }
            ?: return@runCatching null
        match.loadedContextLength ?: match.maxContextLength
    }.getOrNull()

    /**
     * Lista solo los modelos actualmente cargados en memoria (`state == "loaded"`).
     * Devuelve null si el endpoint no existe — el caller debe caer al `/v1/models` estándar.
     */
    suspend fun listLoadedModelIds(baseUrl: String): List<String>? = runCatching {
        fetchAllModels(baseUrl)
            ?.filter { it.state.equals("loaded", ignoreCase = true) }
            ?.map { it.id }
    }.getOrNull()

    internal suspend fun fetchAllModels(baseUrl: String): List<LmStudioModel>? {
        val root = rootOf(baseUrl)
        val token = authTokenProvider()
        val response = client.get("$root/api/v0/models") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) return null
        return response.body<LmStudioModelsResponse>().data
    }

    /**
     * Lista todos los modelos descargados vía la API nativa v1 (LM Studio >= 0.4.0),
     * incluyendo los no cargados. Devuelve null en cualquier fallo — actúa como sonda
     * de capacidad: null significa "este servidor no permite gestionar modelos".
     */
    suspend fun listModelsV1(baseUrl: String): List<LmStudioV1Model>? = runCatching {
        val root = rootOf(baseUrl)
        val token = authTokenProvider()
        val response = client.get("$root/api/v1/models") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) return@runCatching null
        json.decodeFromString(LmStudioV1ModelsResponse.serializer(), response.bodyAsText()).models
    }.getOrNull()

    /**
     * Carga un modelo en memoria (`POST /api/v1/models/load`). Puede tardar varios
     * segundos; el timeout largo del cliente HTTP lo cubre. A diferencia de las
     * lecturas, propaga el error real para que la UI lo muestre.
     */
    suspend fun loadModel(baseUrl: String, modelKey: String): Result<LmStudioLoadResponse> {
        val url = "${rootOf(baseUrl)}/api/v1/models/load"
        val request = LmStudioLoadRequest(model = modelKey)
        val requestJson = json.encodeToString(LmStudioLoadRequest.serializer(), request)
        return postRecorded(url, requestJson, NetworkTransaction.Kind.ModelLoad) { raw ->
            json.decodeFromString(LmStudioLoadResponse.serializer(), raw)
        }
    }

    /** Descarga de memoria la instancia indicada (`POST /api/v1/models/unload`). */
    suspend fun unloadModel(baseUrl: String, instanceId: String): Result<Unit> {
        val url = "${rootOf(baseUrl)}/api/v1/models/unload"
        val request = LmStudioUnloadRequest(instanceId = instanceId)
        val requestJson = json.encodeToString(LmStudioUnloadRequest.serializer(), request)
        return postRecorded(url, requestJson, NetworkTransaction.Kind.ModelUnload) { }
    }

    private suspend fun <T> postRecorded(
        url: String,
        requestJson: String,
        kind: NetworkTransaction.Kind,
        parse: (String) -> T
    ): Result<T> {
        val start = Clock.System.now().toEpochMilliseconds()
        val token = authTokenProvider()
        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(requestJson)
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
                    responseBody = raw,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${raw.take(300)}")
            }
            parse(raw)
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

    private fun rootOf(baseUrl: String): String =
        baseUrl.removeSuffix("/").removeSuffix("/v1").removeSuffix("/")
}
