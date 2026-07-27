package com.localchatbot.data.repository

import com.localchatbot.core.util.newId
import com.localchatbot.data.remote.ChatCompletionRequest
import com.localchatbot.data.remote.FunctionCall
import com.localchatbot.data.remote.LmStudioApi
import com.localchatbot.data.remote.OpenAiApi
import com.localchatbot.data.remote.OpenAiMessage
import com.localchatbot.data.remote.StreamOptions
import com.localchatbot.data.remote.ToolCall
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.data.remote.Usage
import com.localchatbot.domain.model.AvailableModel
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.ModelCatalog
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
        tools: List<ToolDefinition>?,
        generationParams: GenerationParams?
    ): Flow<StreamEvent> = flow {
        val p = generationParams ?: GenerationParams()
        val req = ChatCompletionRequest(
            model = model,
            messages = messages.map { it.toDto() },
            stream = true,
            tools = tools,
            toolChoice = if (tools.isNullOrEmpty()) null else "auto",
            streamOptions = StreamOptions(includeUsage = true),
            temperature = p.temperature,
            topP = p.topP,
            maxTokens = p.maxTokens,
            presencePenalty = p.presencePenalty,
            frequencyPenalty = p.frequencyPenalty,
            seed = p.seed
        )

        // Acumuladores para tool_calls que llegan fragmentados.
        val builders = mutableMapOf<Int, ToolCallBuilder>()
        var finalFinishReason: String? = null
        var actualModel: String? = null
        var usage: Usage? = null
        var firstTokenMs: Long? = null
        var contentChars = 0
        var reasoningStartMs: Long? = null
        var reasoningEndMs: Long? = null

        api.streamChatCompletion(baseUrl, req).collect { chunk ->
            if (actualModel == null) actualModel = chunk.model?.takeIf { it.isNotBlank() }
            chunk.usage?.let { usage = it }
            val choice = chunk.choices.firstOrNull() ?: return@collect
            val delta = choice.delta
            delta?.content?.takeIf { it.isNotEmpty() }?.let {
                if (firstTokenMs == null) firstTokenMs = Clock.System.now().toEpochMilliseconds()
                if (reasoningStartMs != null && reasoningEndMs == null) {
                    reasoningEndMs = Clock.System.now().toEpochMilliseconds()
                }
                contentChars += it.length
                emit(StreamEvent.ContentDelta(it))
            }
            delta?.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                val now = Clock.System.now().toEpochMilliseconds()
                if (firstTokenMs == null) firstTokenMs = now
                if (reasoningStartMs == null) reasoningStartMs = now
                emit(StreamEvent.ReasoningDelta(it))
            }

            delta?.toolCalls?.forEach { d ->
                // Si el server omite `index` (algunos modelos lo hacen cuando solo
                // hay un tool_call), asumimos 0 — equivalente al primero.
                val idx = d.index ?: 0
                val builder = builders.getOrPut(idx) { ToolCallBuilder() }
                d.id?.let { builder.id = it }
                d.function?.name?.let { builder.name = it }
                d.function?.arguments?.let { builder.arguments.append(it) }
            }

            choice.finishReason?.let { finalFinishReason = it }
        }

        val toolCalls = builders.entries
            .sortedBy { it.key }
            .mapNotNull { (_, b) -> b.build() }

        // Métricas: usamos `usage` del servidor si llegó; si no, estimamos los tokens
        // de salida por longitud (~4 chars/token, regla de dedo) y marcamos `estimated`.
        val reportedOutput = usage?.completionTokens
        val outputTokens = reportedOutput ?: (contentChars / 4).takeIf { it > 0 }
        val now = Clock.System.now().toEpochMilliseconds()
        val generationMs = firstTokenMs?.let { now - it }
        val reasoningMs = reasoningStartMs?.let { (reasoningEndMs ?: now) - it }

        emit(
            StreamEvent.Finish(
                reason = finalFinishReason,
                toolCalls = toolCalls,
                actualModel = actualModel,
                inputTokens = usage?.promptTokens,
                outputTokens = outputTokens,
                generationMs = generationMs,
                estimated = reportedOutput == null,
                reasoningMs = reasoningMs
            )
        )
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

    /**
     * Fallback en tres niveles:
     *  1. API v1 de LM Studio (>= 0.4.0): todos los modelos descargados con estado
     *     de carga y soporte de load/unload (`canManage = true`).
     *  2. API v0 de LM Studio (0.3.x): lista con estado pero sin load/unload.
     *  3. `/v1/models` OpenAI estándar (Ollama, llama.cpp…): solo ids, sin estado.
     */
    override suspend fun listModelsDetailed(baseUrl: String): Result<ModelCatalog> {
        lmStudioApi.listModelsV1(baseUrl)?.let { v1 ->
            val models = v1
                .filterNot { it.type.equals("embedding", ignoreCase = true) }
                .map { m ->
                    AvailableModel(
                        id = m.key,
                        displayName = m.displayName,
                        loaded = m.loadedInstances.isNotEmpty(),
                        instanceIds = m.loadedInstances.map { it.id },
                        paramsString = m.paramsString,
                        maxContextLength = m.maxContextLength
                    )
                }
                .sortedByDescending { it.loaded == true }
            return Result.success(ModelCatalog(models, canManage = true))
        }
        runCatching { lmStudioApi.fetchAllModels(baseUrl) }.getOrNull()?.let { v0 ->
            val models = v0
                .filterNot { it.type.equals("embedding", ignoreCase = true) }
                .map { m ->
                    AvailableModel(
                        id = m.id,
                        loaded = m.state.equals("loaded", ignoreCase = true),
                        maxContextLength = m.maxContextLength
                    )
                }
                .sortedByDescending { it.loaded == true }
            return Result.success(ModelCatalog(models, canManage = false))
        }
        return api.listModels(baseUrl).map { ids ->
            ModelCatalog(ids.map { AvailableModel(id = it) }, canManage = false)
        }
    }

    override suspend fun loadModel(baseUrl: String, modelId: String): Result<String> =
        lmStudioApi.loadModel(baseUrl, modelId).map { it.instanceId }

    override suspend fun unloadModel(baseUrl: String, instanceId: String): Result<Unit> =
        lmStudioApi.unloadModel(baseUrl, instanceId)

    override suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? =
        lmStudioApi.fetchContextLength(baseUrl, modelId)

    override suspend fun isModelLoaded(baseUrl: String, modelId: String): Boolean? {
        lmStudioApi.listModelsV1(baseUrl)?.let { v1 ->
            return v1.firstOrNull { it.key == modelId }?.loadedInstances?.isNotEmpty() ?: false
        }
        runCatching { lmStudioApi.fetchAllModels(baseUrl) }.getOrNull()?.let { v0 ->
            return v0.firstOrNull { it.id == modelId }?.state.equals("loaded", ignoreCase = true)
        }
        return null
    }


    override suspend fun generateTitle(
        baseUrl: String,
        model: String,
        userText: String,
        assistantText: String
    ): Result<String> {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                OpenAiMessage.text("system", TITLE_SYSTEM_PROMPT),
                OpenAiMessage.text(
                    "user",
                    "Usuario: ${userText.take(500)}\n\nAssistant: ${assistantText.take(500)}"
                )
            ),
            // Baja temperatura: queremos un título estable y literal, no creativo.
            temperature = 0.2
        )
        return api.chatCompletion(baseUrl, request).mapCatching { response ->
            val raw = response.choices.firstOrNull()?.message?.content?.asText()
                ?: throw IllegalStateException("Respuesta vacía al pedir título")
            sanitizeTitle(raw)
                ?: throw IllegalStateException("Título inutilizable: $raw")
        }
    }

    /**
     * El modelo a veces envuelve el título en comillas, markdown o añade
     * razonamiento previo. Nos quedamos con la última línea no vacía que
     * parezca un título (sin restos de código tipo `foo()`, flechas o JSON)
     * y la recortamos a 60 chars. Bloques <think>…</think> se descartan enteros.
     */
    private fun sanitizeTitle(raw: String): String? {
        val withoutThink = raw.replace(Regex("(?s)<think>.*?(</think>|$)"), "")
        val candidates = withoutThink.lineSequence()
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'").trim('*', '#', '`', ' ') }
            .filter { it.isNotBlank() }
            .toList()
        // Preferir una línea sin pinta de código/diagrama; si todas la tienen, usar la última.
        val codeLike = Regex("""[(){}<>;=→↓↑`]|\w+\.\w+\(""")
        val line = candidates.lastOrNull { !it.contains(codeLike) }
            ?: candidates.lastOrNull()
            ?: return null
        val cleaned = line.removeSuffix(".").trim()
        if (cleaned.isBlank()) return null
        return if (cleaned.length <= 60) cleaned else cleaned.take(60).substringBeforeLast(' ').ifBlank { cleaned.take(60) }
    }

    override suspend fun summarize(baseUrl: String, model: String, transcript: String): String? {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                OpenAiMessage.text("system", SUMMARY_SYSTEM_PROMPT),
                OpenAiMessage.text("user", transcript.take(8000))
            ),
            temperature = 0.3
        )
        return api.chatCompletion(baseUrl, request).getOrNull()
            ?.choices?.firstOrNull()?.message?.content?.asText()
            ?.trim()?.takeIf { it.isNotBlank() }
    }

    override suspend fun generateDocument(
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String? {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                OpenAiMessage.text("system", systemPrompt),
                OpenAiMessage.text("user", userPrompt)
            ),
            temperature = 0.3
        )
        return api.chatCompletion(baseUrl, request).getOrNull()
            ?.choices?.firstOrNull()?.message?.content?.asText()
            ?.trim()?.takeIf { it.isNotBlank() }
    }

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

    private companion object {
        const val TITLE_SYSTEM_PROMPT =
            "You write titles for chat conversations. Given the first user message and the " +
                "assistant reply, respond with ONLY the title: 3 to 6 words, in the same " +
                "language the user wrote in, no quotes, no trailing period, no markdown, " +
                "no explanation."

        const val SUMMARY_SYSTEM_PROMPT =
            "You summarize chat history. Given a conversation transcript, produce a concise " +
                "summary (3-8 sentences) capturing the main task, key decisions, files or " +
                "commands involved, and current state. Write in the same language as the " +
                "conversation. No markdown, no bullet points, no preamble — just the summary."
    }
}
