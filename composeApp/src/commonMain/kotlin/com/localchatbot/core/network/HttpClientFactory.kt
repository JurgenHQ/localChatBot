package com.localchatbot.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun create(): HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            // El streaming de un modelo local puede tardar varios minutos:
            // queremos que el cliente NO mate la conexión por su cuenta.
            requestTimeoutMillis = 10 * 60_000   // 10 min
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 10 * 60_000    // 10 min
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        expectSuccess = false
    }
}
