package com.localchatbot.domain.repository

import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.ModelCatalog
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.TokenMetrics
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
    suspend fun updateMessageVideo(sessionId: String, messageId: String, videoDataUrl: String)
    suspend fun updateMessageReasoning(sessionId: String, messageId: String, reasoning: String)
    suspend fun updateMessageCheckpoint(sessionId: String, messageId: String, checkpointId: String)
    suspend fun updateMessageMetrics(sessionId: String, messageId: String, metrics: TokenMetrics)
    suspend fun updateTitle(sessionId: String, title: String)
    suspend fun updateModel(sessionId: String, model: String)
    suspend fun setPinned(sessionId: String, pinned: Boolean)
    suspend fun updateContextSummary(sessionId: String, summary: String)
    /** Elimina el mensaje indicado y todos los posteriores en esa sesión. */
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String)
    suspend fun clearAll()

    /**
     * Fuerza la escritura inmediata de cualquier persistencia pendiente
     * (la escritura a disco va con throttle). Llamar al cerrar la app.
     */
    fun flushPendingWrites()
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
        tools: List<ToolDefinition>?,
        generationParams: GenerationParams? = null
    ): Flow<StreamEvent>

    suspend fun ping(baseUrl: String): Result<Long>

    suspend fun listModels(baseUrl: String): Result<List<String>>

    /**
     * Lista modelos con estado de carga cuando el backend lo expone. Con LM Studio
     * >= 0.4.0 incluye todos los modelos descargados (aunque no estén en memoria)
     * y `canManage = true`; con otros backends degrada a la lista simple.
     */
    suspend fun listModelsDetailed(baseUrl: String): Result<ModelCatalog>

    /** Carga un modelo en memoria (LM Studio v1). Devuelve el instance_id. */
    suspend fun loadModel(baseUrl: String, modelId: String): Result<String>

    /** Descarga de memoria la instancia indicada (LM Studio v1). */
    suspend fun unloadModel(baseUrl: String, instanceId: String): Result<Unit>

    /** Devuelve la longitud de contexto del modelo si el servidor la expone (LM Studio). */
    suspend fun fetchContextLength(baseUrl: String, modelId: String): Int?

    /**
     * Indica si el modelo está cargado en memoria ahora mismo. Null si el servidor no
     * expone esa información (backend OpenAI plano) — en ese caso no se puede distinguir
     * "descargado" de "desconocido", así que la UI no debe mostrar un aviso de descarga.
     */
    suspend fun isModelLoaded(baseUrl: String, modelId: String): Boolean?

    /**
     * Genera un título corto (3-6 palabras) para una sesión a partir del primer
     * intercambio usuario→assistant. Llamado en background tras la primera
     * respuesta; si falla se conserva el título placeholder.
     */
    suspend fun generateTitle(
        baseUrl: String,
        model: String,
        userText: String,
        assistantText: String
    ): Result<String>

    /**
     * Genera un resumen compacto del tramo de historial descartado por truncación.
     * Se llama en background; si falla devuelve null y se usa la nota de truncado simple.
     */
    suspend fun summarize(
        baseUrl: String,
        model: String,
        transcript: String
    ): String?
}
