package com.localchatbot.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cliente para los endpoints nativos de **Ollama** (`/api/...`). Por el endpoint OpenAI
 * (`/v1`) Ollama no dice con qué contexto corre el modelo, y ese contexto casi nunca es el
 * máximo del modelo: es el `num_ctx` del Modelfile o el valor por defecto del servidor
 * (unos pocos miles de tokens). Sin conocerlo, la ventana asumía 32k y Ollama recortaba en
 * silencio el principio de la conversación. Si el servidor no es Ollama devuelve null.
 */
class OllamaApi(
    private val client: HttpClient,
    private val authTokenProvider: suspend () -> String? = { null }
) {

    /**
     * Contexto efectivo de [modelId], por orden de fiabilidad:
     * 1. `GET /api/ps` → `context_length` del modelo **cargado**: es el valor real con el que
     *    corre, incluido el que fija `OLLAMA_CONTEXT_LENGTH`. Solo existe mientras el modelo
     *    está en memoria (tras la primera petición).
     * 2. `POST /api/show` → `num_ctx` de los parámetros del Modelfile.
     *
     * No se usa `model_info.*.context_length` de `/api/show`: es el máximo con el que se
     * entrenó el modelo (128k en muchos), no el que usa Ollama, y sobreestimar es justo el
     * problema que esto resuelve.
     */
    suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? = runCatching {
        if (modelId.isBlank()) return@runCatching null
        val root = baseUrl.removeSuffix("/").removeSuffix("/v1").removeSuffix("/")
        val token = authTokenProvider()
        val ps = client.get("$root/api/ps") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (ps.status.isSuccess()) {
            loadedContextLength(ps.body<JsonObject>(), modelId)?.let { return@runCatching it }
        }
        val show = client.post("$root/api/show") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("model", modelId) })
        }
        if (!show.status.isSuccess()) return@runCatching null
        show.body<JsonObject>()["parameters"]?.jsonPrimitive?.contentOrNull?.let(::numCtxFromParameters)
    }.getOrNull()

    companion object {
        /** `context_length` del modelo cargado en la respuesta de `/api/ps`, o null. */
        fun loadedContextLength(ps: JsonObject, modelId: String): Int? {
            val models = ps["models"]?.jsonArray ?: return null
            return models.firstNotNullOfOrNull { el ->
                val m = el.jsonObject
                val names = listOfNotNull(
                    m["name"]?.jsonPrimitive?.contentOrNull,
                    m["model"]?.jsonPrimitive?.contentOrNull
                )
                if (names.none { sameModel(it, modelId) }) return@firstNotNullOfOrNull null
                m["context_length"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            }
        }

        /** `num_ctx` del texto `parameters` de `/api/show` (una línea `num_ctx   8192`). */
        fun numCtxFromParameters(parameters: String): Int? =
            Regex("""(?m)^\s*num_ctx\s+(\d+)\s*$""").find(parameters)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        /** Ollama lista `llama3:latest` aunque la petición diga `llama3`. */
        private fun sameModel(a: String, b: String): Boolean =
            a.removeSuffix(":latest").equals(b.removeSuffix(":latest"), ignoreCase = true)
    }
}
