package com.localchatbot.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class McpTransportConfig {
    @Serializable
    @SerialName("stdio")
    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap()
    ) : McpTransportConfig()

    @Serializable
    @SerialName("http")
    data class Http(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : McpTransportConfig()
}

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransportConfig,
    val enabled: Boolean = true
)
