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
            toolChoice = if (tools.isNullOrEmpty()) null else "auto",
            // Pide tokens (usage) en el chunk final. Servidores que no lo soportan
            // ignoran el campo; si aun así no llega usage, estimamos por longitud.
            streamOptions = StreamOptions(includeUsage = true)
            // Temperatura: dejamos el default del servidor (suele ser ~0.7) para que
            // las conversaciones normales suenen naturales. El precio es que la
            // decisión "¿invoco la tool?" no es 100% determinista — pero cuando hay
            // key configurada y el prompt es claro, los modelos modernos suelen
            // invocarla de forma consistente.
        )

        // Acumuladores para tool_calls que llegan fragmentados.
        val builders = mutableMapOf<Int, ToolCallBuilder>()
        var finalFinishReason: String? = null
        var actualModel: String? = null
        var usage: Usage? = null
        var firstTokenMs: Long? = null
        var contentChars = 0

        api.streamChatCompletion(baseUrl, req).collect { chunk ->
            if (actualModel == null) actualModel = chunk.model?.takeIf { it.isNotBlank() }
            chunk.usage?.let { usage = it }
            val choice = chunk.choices.firstOrNull() ?: return@collect
            val delta = choice.delta
            delta?.content?.takeIf { it.isNotEmpty() }?.let {
                if (firstTokenMs == null) firstTokenMs = Clock.System.now().toEpochMilliseconds()
                contentChars += it.length
                emit(StreamEvent.ContentDelta(it))
            }
            delta?.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                if (firstTokenMs == null) firstTokenMs = Clock.System.now().toEpochMilliseconds()
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
        val generationMs = firstTokenMs?.let { Clock.System.now().toEpochMilliseconds() - it }

        emit(
            StreamEvent.Finish(
                reason = finalFinishReason,
                toolCalls = toolCalls,
                actualModel = actualModel,
                inputTokens = usage?.promptTokens,
                outputTokens = outputTokens,
                generationMs = generationMs,
                estimated = reportedOutput == null
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

    override suspend fun fetchContextLength(baseUrl: String, modelId: String): Int? =
        lmStudioApi.fetchContextLength(baseUrl, modelId)

    override suspend fun generateSuggestions(
        baseUrl: String,
        model: String
    ): Result<List<String>> {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                OpenAiMessage.text("system", SUGGESTIONS_SYSTEM_PROMPT),
                OpenAiMessage.text("user", SUGGESTIONS_USER_PROMPT)
            ),
            // Subimos un poco la temperatura para que las sugerencias varíen entre llamadas;
            // si la dejamos al default del servidor (~0.7) tienden a repetirse.
            temperature = 0.9
        )
        return api.chatCompletion(baseUrl, request).mapCatching { response ->
            val raw = response.choices.firstOrNull()?.message?.content?.asText()
                ?: throw IllegalStateException("Respuesta vacía al pedir sugerencias")
            parseSuggestionsArray(raw)
                ?: throw IllegalStateException("No se pudo parsear el JSON de sugerencias: $raw")
        }
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

    /**
     * El modelo a veces envuelve el JSON en bloques de código (```json … ```) o
     * añade texto extra antes/después. Extraemos el primer array balanceado
     * `[...]` y lo decodificamos. Si lo que queda dentro no son strings, devolvemos null.
     */
    private fun parseSuggestionsArray(raw: String): List<String>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val slice = raw.substring(start, end + 1)
        return runCatching {
            val arr = suggestionsJson.parseToJsonElement(slice) as? JsonArray ?: return null
            arr.map { (it as JsonPrimitive).content }
                .filter { it.isNotBlank() }
                .take(3)
                .takeIf { it.size == 3 }
        }.getOrNull()
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

    private val suggestionsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

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

        const val SUGGESTIONS_SYSTEM_PROMPT =
            "You generate short example prompts for a chat app. Reply with ONLY a JSON array " +
                "of exactly 3 strings, no markdown fences, no commentary, no extra text."

        val SUGGESTIONS_USER_PROMPT = """
            Genera 3 ejemplos de prompts en español que un usuario podría tocar para empezar una conversación.
            Cada uno debe ser una pregunta o instrucción concreta, en una sola frase, idealmente menos de 80 caracteres.

            Categorías exactas y en este orden:
            1. Desarrollo de software o programación.
            2. Noticias o eventos actuales (algo que se beneficie de buscar en internet hoy).
            3. Tema random, creativo, divertido o sorprendente.

            Devuelve SOLO un JSON array con 3 strings. Ejemplo de formato:
            ["...", "...", "..."]
        """.trimIndent()
    }
}
