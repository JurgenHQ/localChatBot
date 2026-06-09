package com.localchatbot.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
    val index: Int? = null
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class ToolCallDelta(
    // Algunos servers/modelos (LM Studio con ciertos modelos, Ollama, fine-tunes
    // sin el formato estricto de OpenAI) omiten `index` cuando solo hay un tool_call.
    // Lo dejamos nullable para evitar que el chunk completo falle al parsear: el
    // consumidor asume 0 si falta.
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDelta? = null
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null
)
