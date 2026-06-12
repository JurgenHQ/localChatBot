package com.localchatbot.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Cliente para la API extendida de **LM Studio** (`/api/v0/...`). No forma parte del
 * estándar OpenAI: la usamos cuando el servidor la expone para enriquecer la UX
 * (longitud de contexto real, estado del modelo, etc.). Si el servidor es Ollama,
 * llama.cpp o cualquier otro, estas llamadas devolverán null silenciosamente y
 * seguimos funcionando con los endpoints OpenAI estándar de [OpenAiApi].
 */
class LmStudioApi(
    private val client: HttpClient,
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

    private suspend fun fetchAllModels(baseUrl: String): List<LmStudioModel>? {
        val root = baseUrl.removeSuffix("/v1").removeSuffix("/")
        val token = authTokenProvider()
        val response = client.get("$root/api/v0/models") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) return null
        return response.body<LmStudioModelsResponse>().data
    }
}
