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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TavilyRequest(
    @SerialName("api_key") val apiKey: String,
    val query: String,
    @SerialName("search_depth") val searchDepth: String = "basic",
    @SerialName("max_results") val maxResults: Int = 5,
    @SerialName("include_answer") val includeAnswer: Boolean = true,
    @SerialName("include_raw_content") val includeRawContent: Boolean = false
)

@Serializable
data class TavilyResponse(
    val query: String? = null,
    val answer: String? = null,
    val results: List<TavilyResult> = emptyList(),
    @SerialName("response_time") val responseTime: Double? = null
)

@Serializable
data class TavilyResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val score: Double? = null
)

class TavilyApi(
    private val client: HttpClient,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) {

    suspend fun search(apiKey: String, query: String): Result<TavilyResponse> {
        val requestJson = runCatching {
            json.encodeToString(
                TavilyRequest.serializer(),
                TavilyRequest(apiKey = "***", query = query)
            )
        }.getOrNull()
        val start = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            val response = client.post(ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(TavilyRequest(apiKey = apiKey, query = query))
            }
            val raw = response.bodyAsText()

            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = ENDPOINT,
                    kind = NetworkTransaction.Kind.WebSearch,
                    requestBody = requestJson,
                    responseStatus = response.status.value,
                    responseBody = raw,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start
                )
            )

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Tavily HTTP ${response.status.value}: ${response.status.description}")
            }

            json.decodeFromString(TavilyResponse.serializer(), raw)
        }.onFailure { err ->
            inspector?.record(
                NetworkTransaction(
                    id = inspector.newId(),
                    timestampEpochMs = start,
                    method = "POST",
                    url = ENDPOINT,
                    kind = NetworkTransaction.Kind.WebSearch,
                    requestBody = requestJson,
                    responseStatus = null,
                    responseBody = null,
                    durationMs = Clock.System.now().toEpochMilliseconds() - start,
                    error = err.message
                )
            )
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.tavily.com/search"
    }
}
