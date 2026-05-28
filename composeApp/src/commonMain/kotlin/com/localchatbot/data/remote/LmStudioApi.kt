package com.localchatbot.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

/**
 * Cliente para la API extendida de **LM Studio** (`/api/v0/...`). No forma parte del
 * estándar OpenAI: la usamos cuando el servidor la expone para enriquecer la UX
 * (longitud de contexto real, estado del modelo, etc.). Si el servidor es Ollama,
 * llama.cpp o cualquier otro, estas llamadas devolverán null silenciosamente y
 * seguimos funcionando con los endpoints OpenAI estándar de [OpenAiApi].
 */
class LmStudioApi(private val client: HttpClient) {

    /**
     * Devuelve la longitud de contexto del modelo indicado, o null si:
     *  - el servidor no expone `/api/v0/models` (no es LM Studio),
     *  - el modelo no aparece en la lista,
     *  - el JSON no trae los campos esperados.
     */
    suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? = runCatching {
        val match = fetchAllModels(baseUrl)?.firstOrNull { it.id == modelId } ?: return@runCatching null
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
        val response = client.get("$root/api/v0/models")
        if (!response.status.isSuccess()) return null
        return response.body<LmStudioModelsResponse>().data
    }
}
