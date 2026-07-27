package com.localchatbot.domain.repository

import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.ModelCatalog
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.SessionSummary
import com.localchatbot.domain.model.TokenMetrics
import com.localchatbot.domain.model.WebSource
import com.localchatbot.domain.search.MessageSearchResult
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /**
     * Metadatos de todas las sesiones, **sin mensajes**, ordenados igual que antes
     * (fijadas primero, luego por fecha de actualización). Es lo que consumen el drawer y
     * el listado del acceso remoto, que no leen ni un campo de mensaje.
     *
     * Sustituye al antiguo `sessions: Flow<List<ChatSession>>`, que reconstruía el
     * historial completo de todas las sesiones en cada escritura — incluido cada flush de
     * delta de streaming (cada 120 ms), con coste O(historial entero). Quien necesite los
     * mensajes de **una** sesión usa [sessionWithMessages]; quien necesite una sesión
     * suelta y puntual, [getSession].
     */
    val sessionSummaries: Flow<List<SessionSummary>>

    /**
     * Sesión completa (metadatos + mensajes + media transitoria) de [sessionId], reactiva.
     * Emite null si la sesión no existe o deja de existir.
     *
     * Solo se colecta para la sesión **activa**, así que durante el streaming se
     * deserializan únicamente los mensajes que están en pantalla.
     */
    fun sessionWithMessages(sessionId: String): Flow<ChatSession?>

    /**
     * Data URL de la imagen de un mensaje, o null. Se lee del overlay en memoria: las
     * imágenes/vídeos nunca se persisten (ver [com.localchatbot.domain.model.ChatMessage]),
     * así que no hace falta tocar SQLite para resolverlas.
     */
    fun messageImageDataUrl(messageId: String): String?

    /**
     * Busca [query] en el contenido de **todos** los mensajes, vía el índice FTS5
     * `message_fts`. Devuelve los resultados ordenados por relevancia (bm25), como mucho
     * [limit].
     *
     * Es one-shot y no un `Flow`: la búsqueda la dispara el usuario al escribir, y
     * reemitirla en cada escritura de streaming solo gastaría trabajo en resultados que
     * nadie está mirando.
     */
    suspend fun searchMessages(query: String, limit: Int = 50): List<MessageSearchResult>

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
    /**
     * Cierra el turno escribiendo métricas y modelo juntos: [model] es el que reportó el
     * servidor (puede no ser el configurado) y no se conoce hasta este momento, igual que
     * las métricas. Van en la misma llamada para no gastar dos transacciones en lo mismo.
     * Null en [model] deja la columna como está.
     */
    suspend fun updateMessageMetrics(sessionId: String, messageId: String, metrics: TokenMetrics, model: String?)
    suspend fun updateTitle(sessionId: String, title: String)
    suspend fun updateModel(sessionId: String, model: String)
    suspend fun setPinned(sessionId: String, pinned: Boolean)
    suspend fun updateContextSummary(sessionId: String, summary: String)
    /** Elimina el mensaje indicado y todos los posteriores en esa sesión. */
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String)

    /**
     * Crea una copia completa de la sesión (mensajes incluidos) como sesión nueva, para
     * conservar una rama antes de truncar el historial. La copia no queda fijada y sus
     * mensajes reciben ids nuevos; el `checkpointId` NO se copia, porque los checkpoints
     * están indexados por sesión de origen y el chip de revertir apuntaría a un turno que
     * en la copia no existe.
     *
     * Devuelve la sesión creada, o null si la de origen ya no existe.
     */
    suspend fun forkSession(sessionId: String): ChatSession?
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

    /**
     * Completion no interactiva de propósito general con system prompt e input libres
     * (a diferencia de [summarize]/[generateTitle], que tienen su prompt fijo). La usa
     * `InitProjectUseCase` para redactar AGENTS.md a partir del contexto del workspace.
     */
    suspend fun generateDocument(
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String?
}
