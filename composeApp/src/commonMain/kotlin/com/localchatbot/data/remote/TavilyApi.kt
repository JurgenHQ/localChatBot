package com.localchatbot.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

class TavilyApi(private val client: HttpClient) {

    suspend fun search(apiKey: String, query: String): Result<TavilyResponse> = runCatching {
        val response = client.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(TavilyRequest(apiKey = apiKey, query = query))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Tavily HTTP ${response.status.value}: ${response.status.description}")
        }
        response.body<TavilyResponse>()
    }

    companion object {
        private const val ENDPOINT = "https://api.tavily.com/search"
    }
}
