package com.localchatbot.domain.repository

import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.WebSource
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    val sessions: Flow<List<ChatSession>>
    suspend fun createSession(model: String): ChatSession
    suspend fun deleteSession(id: String)
    suspend fun getSession(id: String): ChatSession?
    suspend fun appendMessage(sessionId: String, message: ChatMessage)
    suspend fun updateMessageContent(sessionId: String, messageId: String, content: String)
    suspend fun updateMessageToolCalls(sessionId: String, messageId: String, toolCalls: List<PersistedToolCall>)
    suspend fun updateMessageSources(sessionId: String, messageId: String, sources: List<WebSource>)
    suspend fun updateMessageImage(sessionId: String, messageId: String, imageDataUrl: String)
    suspend fun updateTitle(sessionId: String, title: String)
    suspend fun setPinned(sessionId: String, pinned: Boolean)
    /** Elimina el mensaje indicado y todos los posteriores en esa sesión. */
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String)
    suspend fun clearAll()
}

interface ModelRepository {
    suspend fun sendChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>
    ): Result<ChatMessage>

    fun streamChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>
    ): Flow<String>

    fun streamChatWithTools(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?
    ): Flow<StreamEvent>

    suspend fun ping(baseUrl: String): Result<Long>

    suspend fun listModels(baseUrl: String): Result<List<String>>

    /** Devuelve la longitud de contexto del modelo si el servidor la expone (LM Studio). */
    suspend fun fetchContextLength(baseUrl: String, modelId: String): Int?
}
