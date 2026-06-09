package com.localchatbot.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
) {
    companion object {
        fun text(role: String, text: String) = OpenAiMessage(role = role, content = JsonPrimitive(text))

        fun multimodal(role: String, text: String, imageDataUrl: String) = OpenAiMessage(
            role = role,
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", imageDataUrl) })
                })
            }
        )

        fun assistantWithToolCalls(toolCalls: List<ToolCall>) = OpenAiMessage(
            role = "assistant",
            content = null,
            toolCalls = toolCalls
        )

        fun toolResult(toolCallId: String, result: String, name: String? = null) = OpenAiMessage(
            role = "tool",
            content = JsonPrimitive(result),
            toolCallId = toolCallId,
            name = name
        )
    }
}

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: OpenAiMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )
}

@Serializable
data class ModelsResponse(
    val data: List<ModelInfo> = emptyList()
) {
    @Serializable
    data class ModelInfo(val id: String)
}


@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList()
) {
    @Serializable
    data class ChunkChoice(
        val index: Int = 0,
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        // Modelos con chain-of-thought (Gemma 3/4, QwQ, DeepSeek-R1, o1-like) emiten
        // su razonamiento aquí en lugar de en `content`. Si el modelo NUNCA emite
        // `content` (común con prompts mal calibrados o context length pequeño), la UI
        // se quedaba vacía. Lo capturamos para poder mostrarlo al usuario.
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null
    )
}
