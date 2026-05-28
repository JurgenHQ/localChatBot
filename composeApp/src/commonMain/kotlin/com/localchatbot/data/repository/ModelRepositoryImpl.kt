package com.localchatbot.data.repository

import com.localchatbot.data.remote.ChatCompletionRequest
import com.localchatbot.data.remote.FunctionCall
import com.localchatbot.data.remote.LmStudioApi
import com.localchatbot.data.remote.OpenAiApi
import com.localchatbot.data.remote.OpenAiMessage
import com.localchatbot.data.remote.ToolCall
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

class ModelRepositoryImpl(
    private val api: OpenAiApi,
    private val lmStudioApi: LmStudioApi
) : ModelRepository {

    override suspend fun sendChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>
    ): Result<ChatMessage> {
        val req = ChatCompletionRequest(
            model = model,
            messages = messages.map { it.toDto() }
        )
        return api.chatCompletion(baseUrl, req).mapCatching { response ->
            val text = response.choices.firstOrNull()?.message?.content?.asText()
                ?: throw IllegalStateException("Respuesta vacía del modelo")
            ChatMessage(
                id = newId(),
                role = Role.Assistant,
                content = text,
                timestampEpochMs = Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    override fun streamChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>
    ): Flow<String> {
        val req = ChatCompletionRequest(
            model = model,
            messages = messages.map { it.toDto() },
            stream = true
        )
        return api.streamChatCompletion(baseUrl, req)
            .mapNotNull { chunk -> chunk.choices.firstOrNull()?.delta?.content }
    }

    override fun streamChatWithTools(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?
    ): Flow<StreamEvent> = flow {
        val req = ChatCompletionRequest(
            model = model,
            messages = messages.map { it.toDto() },
            stream = true,
            tools = tools,
            toolChoice = if (tools.isNullOrEmpty()) null else "auto"
            // Temperatura: dejamos el default del servidor (suele ser ~0.7) para que
            // las conversaciones normales suenen naturales. El precio es que la
            // decisión "¿invoco la tool?" no es 100% determinista — pero cuando hay
            // key configurada y el prompt es claro, los modelos modernos suelen
            // invocarla de forma consistente.
        )

        // Acumuladores para tool_calls que llegan fragmentados.
        val builders = mutableMapOf<Int, ToolCallBuilder>()
        var finalFinishReason: String? = null

        api.streamChatCompletion(baseUrl, req).collect { chunk ->
            val choice = chunk.choices.firstOrNull() ?: return@collect
            val delta = choice.delta
            delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.ContentDelta(it)) }

            delta?.toolCalls?.forEach { d ->
                val builder = builders.getOrPut(d.index) { ToolCallBuilder() }
                d.id?.let { builder.id = it }
                d.function?.name?.let { builder.name = it }
                d.function?.arguments?.let { builder.arguments.append(it) }
            }

            choice.finishReason?.let { finalFinishReason = it }
        }

        val toolCalls = builders.entries
            .sortedBy { it.key }
            .mapNotNull { (_, b) -> b.build() }

        emit(StreamEvent.Finish(reason = finalFinishReason, toolCalls = toolCalls))
    }

    override suspend fun ping(baseUrl: String): Result<Long> = api.ping(baseUrl)

    /**
     * Si el servidor es LM Studio, listamos solo los modelos cargados en memoria — son
     * los que realmente pueden responder ahora mismo. Para Ollama/llama.cpp/otros caemos
     * al `/v1/models` OpenAI estándar, que devuelve todos los disponibles.
     */
    override suspend fun listModels(baseUrl: String): Result<List<String>> {
        val loaded = lmStudioApi.listLoadedModelIds(baseUrl)
        if (loaded != null) return Result.success(loaded)
        return api.listModels(baseUrl)
    }

    override suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? =
        lmStudioApi.fetchContextLength(baseUrl, modelId)

    private fun ChatMessage.toDto(): OpenAiMessage {
        val roleString = when (role) {
            Role.User -> "user"
            Role.Assistant -> "assistant"
            Role.System -> "system"
            Role.Tool -> "tool"
        }
        return when {
            role == Role.Tool -> OpenAiMessage.toolResult(
                toolCallId = toolCallId ?: "",
                result = content,
                name = toolName
            )
            role == Role.Assistant && !toolCalls.isNullOrEmpty() -> OpenAiMessage.assistantWithToolCalls(
                toolCalls = toolCalls.map { ToolCall(id = it.id, function = FunctionCall(it.name, it.argumentsJson)) }
            )
            role == Role.User && imageDataUrl != null -> OpenAiMessage.multimodal(roleString, content, imageDataUrl)
            else -> OpenAiMessage.text(roleString, content)
        }
    }

    private fun JsonElement.asText(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> firstNotNullOfOrNull { el ->
            (el as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }
        else -> null
    }

    private fun newId(): String =
        Clock.System.now().toEpochMilliseconds().toString(36) +
            "-" + Random.nextInt(0, 1_000_000).toString(36)

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        fun build(): ToolCall? {
            val resolvedId = id ?: return null
            val resolvedName = name ?: return null
            return ToolCall(
                id = resolvedId,
                type = "function",
                function = FunctionCall(name = resolvedName, arguments = arguments.toString())
            )
        }
    }
}
