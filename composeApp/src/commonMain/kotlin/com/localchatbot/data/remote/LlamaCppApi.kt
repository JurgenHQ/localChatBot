package com.localchatbot.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cliente para los endpoints propios de **llama.cpp** (`llama-server`). No forman parte
 * del estándar OpenAI: los usamos para conocer el contexto con el que se levantó el
 * servidor (`-c` / `--ctx-size`). Si el servidor no es llama.cpp devuelve null
 * silenciosamente.
 */
class LlamaCppApi(
    private val client: HttpClient,
    /** Misma API key que [OpenAiApi]: se envía como `Authorization: Bearer` si llama-server usa `--api-key`. */
    private val authTokenProvider: suspend () -> String? = { null }
) {

    /**
     * Contexto real por petición según `GET /props` → `default_generation_settings.n_ctx`.
     * Es el contexto de un slot: con `--parallel N` llama-server reparte `--ctx-size`
     * entre los N slots, así que este es el límite efectivo de una conversación.
     *
     * Se manda `?model=` para el modo router (varios modelos en un mismo servidor);
     * en modo normal llama-server ignora el parámetro.
     */
    suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? = runCatching {
        val root = baseUrl.removeSuffix("/").removeSuffix("/v1").removeSuffix("/")
        val token = authTokenProvider()
        val response = client.get("$root/props") {
            if (modelId.isNotBlank()) parameter("model", modelId)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) return@runCatching null
        val props = response.body<JsonObject>()
        val fromDefaults = props["default_generation_settings"]
            ?.jsonObject?.get("n_ctx")?.jsonPrimitive?.intOrNull
        val topLevel = props["n_ctx"]?.jsonPrimitive?.intOrNull
        (fromDefaults ?: topLevel)?.takeIf { it > 0 }
    }.getOrNull()
}
