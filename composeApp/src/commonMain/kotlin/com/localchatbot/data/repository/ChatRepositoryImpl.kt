package com.localchatbot.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.localchatbot.core.util.newId
import com.localchatbot.data.local.db.LocalChatBotDatabase
import com.localchatbot.data.local.db.Message as DbMessage
import com.localchatbot.data.local.db.SelectAllSessionSummaries as DbSessionSummary
import com.localchatbot.data.local.db.Session as DbSession
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.model.SessionSummary
import com.localchatbot.domain.model.TokenMetrics
import com.localchatbot.domain.model.WebSource
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.search.MessageSearchResult
import com.localchatbot.domain.search.toFtsMatchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Sesiones y mensajes persistidos vía SQLDelight (SQLite), transaccional y resistente a
 * corrupción parcial — reemplaza la implementación anterior sobre `multiplatform-settings`
 * (ver [com.localchatbot.data.repository.legacy.LegacySettingsChatRepository], que se
 * conserva solo como lector de origen para la migración one-shot).
 */
class ChatRepositoryImpl(
    private val db: LocalChatBotDatabase,
    @Suppress("UNUSED_PARAMETER") private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ChatRepository {

    /**
     * imageDataUrl/videoDataUrl son base64 transitorios (nunca se persisten, ni aquí ni en
     * la implementación legacy) y por eso no tienen columna en `message`. Es el único estado
     * de este repo que no vive en SQLite — si se refactoriza, mantener este comportamiento.
     */
    private val mediaOverlay = MutableStateFlow<Map<String, Pair<String?, String?>>>(emptyMap())

    /**
     * Metadatos sin mensajes. No se combina con [mediaOverlay] a propósito: el drawer no
     * muestra imágenes, así que generar una imagen no tiene por qué re-emitir la lista.
     *
     * El preview del último mensaje lo resuelve SQLite dentro de la propia consulta (ver
     * `selectAllSessionSummaries`), así que aquí no se toca la tabla `message`.
     */
    override val sessionSummaries: Flow<List<SessionSummary>> =
        db.sessionQueries.selectAllSessionSummaries(Role.Tool).asFlow().mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    /**
     * Sesión completa. Las dos consultas son por `sessionId`, así que el coste va con el
     * tamaño de **esa** sesión y no con el del historial entero — que era justo el problema
     * del antiguo `sessions`, donde cada delta de streaming re-leía y deserializaba todos
     * los mensajes de todas las sesiones.
     */
    override fun sessionWithMessages(sessionId: String): Flow<ChatSession?> = combine(
        db.sessionQueries.selectSessionById(sessionId).asFlow().mapToOneOrNull(ioDispatcher),
        db.messageQueries.selectMessagesBySession(sessionId).asFlow().mapToList(ioDispatcher),
        mediaOverlay
    ) { dbSession, dbMessages, overlay ->
        dbSession?.toDomain(
            dbMessages.map { m ->
                val (image, video) = overlay[m.id] ?: (null to null)
                m.toDomain(image, video)
            }
        )
    }

    override fun messageImageDataUrl(messageId: String): String? = mediaOverlay.value[messageId]?.first

    override suspend fun searchMessages(query: String, limit: Int): List<MessageSearchResult> {
        val match = toFtsMatchQuery(query) ?: return emptyList()
        return withContext(ioDispatcher) {
            // Una expresión MATCH inválida hace que SQLite lance, no que devuelva cero filas.
            // `toFtsMatchQuery` entrecomilla cada término justamente para que eso no pase, pero
            // una búsqueda no puede tumbar la pantalla de sesiones: ante la duda, sin resultados.
            runCatching {
                db.messageQueries.searchMessages(match, Role.Tool, limit.toLong()).executeAsList().map { row ->
                    MessageSearchResult(
                        messageId = row.id,
                        sessionId = row.session_id,
                        sessionTitle = row.title,
                        role = row.role,
                        timestampEpochMs = row.timestamp_epoch_ms,
                        snippet = row.fragment.orEmpty()
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun createSession(model: String): ChatSession {
        val now = Clock.System.now().toEpochMilliseconds()
        val session = ChatSession(
            id = newId(),
            title = "Nueva conversación",
            model = model,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            messages = emptyList()
        )
        withContext(ioDispatcher) {
            db.sessionQueries.insertSession(
                id = session.id,
                title = session.title,
                model = session.model,
                created_at_epoch_ms = session.createdAtEpochMs,
                updated_at_epoch_ms = session.updatedAtEpochMs,
                pinned = session.pinned,
                generation_params = session.generationParams,
                context_summary = session.contextSummary
            )
        }
        return session
    }

    override suspend fun deleteSession(id: String) {
        withContext(ioDispatcher) { db.sessionQueries.deleteSession(id) }
    }

    override suspend fun getSession(id: String): ChatSession? = withContext(ioDispatcher) {
        val s = db.sessionQueries.selectSessionById(id).executeAsOneOrNull() ?: return@withContext null
        val messages = db.messageQueries.selectMessagesBySession(id).executeAsList().map { m ->
            val (image, video) = mediaOverlay.value[m.id] ?: (null to null)
            m.toDomain(image, video)
        }
        s.toDomain(messages)
    }

    override suspend fun appendMessage(sessionId: String, message: ChatMessage) {
        withContext(ioDispatcher) {
            db.transaction {
                val nextOrder = (db.messageQueries.maxSortOrderForSession(sessionId).executeAsOne().MAX ?: -1) + 1
                db.messageQueries.insertMessage(
                    id = message.id,
                    session_id = sessionId,
                    role = message.role,
                    content = message.content,
                    timestamp_epoch_ms = message.timestampEpochMs,
                    sort_order = nextOrder,
                    attachments = message.attachments,
                    tool_calls = message.toolCalls,
                    tool_call_id = message.toolCallId,
                    tool_name = message.toolName,
                    sources = message.sources,
                    reasoning = message.reasoning,
                    metrics = message.metrics,
                    checkpoint_id = message.checkpointId,
                    model = message.model
                )
                db.sessionQueries.updateSessionTimestamp(message.timestampEpochMs, sessionId)
            }
        }
        if (message.imageDataUrl != null || message.videoDataUrl != null) {
            mediaOverlay.update { it + (message.id to (message.imageDataUrl to message.videoDataUrl)) }
        }
    }

    override suspend fun updateMessageContent(sessionId: String, messageId: String, content: String) {
        touchSession(sessionId) { db.messageQueries.updateMessageContent(content, messageId) }
    }

    override suspend fun updateMessageToolCalls(
        sessionId: String,
        messageId: String,
        toolCalls: List<PersistedToolCall>
    ) {
        touchSession(sessionId) { db.messageQueries.updateMessageToolCalls(toolCalls, messageId) }
    }

    override suspend fun updateMessageSources(sessionId: String, messageId: String, sources: List<WebSource>) {
        touchSession(sessionId) { db.messageQueries.updateMessageSources(sources, messageId) }
    }

    override suspend fun updateMessageImage(sessionId: String, messageId: String, imageDataUrl: String) {
        mediaOverlay.update { overlay ->
            val (_, video) = overlay[messageId] ?: (null to null)
            overlay + (messageId to (imageDataUrl to video))
        }
        withContext(ioDispatcher) {
            db.sessionQueries.updateSessionTimestamp(Clock.System.now().toEpochMilliseconds(), sessionId)
        }
    }

    override suspend fun updateMessageVideo(sessionId: String, messageId: String, videoDataUrl: String) {
        mediaOverlay.update { overlay ->
            val (image, _) = overlay[messageId] ?: (null to null)
            overlay + (messageId to (image to videoDataUrl))
        }
        withContext(ioDispatcher) {
            db.sessionQueries.updateSessionTimestamp(Clock.System.now().toEpochMilliseconds(), sessionId)
        }
    }

    override suspend fun updateMessageReasoning(sessionId: String, messageId: String, reasoning: String) {
        touchSession(sessionId) { db.messageQueries.updateMessageReasoning(reasoning, messageId) }
    }

    override suspend fun updateMessageCheckpoint(sessionId: String, messageId: String, checkpointId: String) {
        touchSession(sessionId) { db.messageQueries.updateMessageCheckpoint(checkpointId, messageId) }
    }

    override suspend fun updateMessageMetrics(
        sessionId: String,
        messageId: String,
        metrics: TokenMetrics,
        model: String?
    ) {
        touchSession(sessionId) {
            db.messageQueries.updateMessageMetrics(metrics, messageId)
            if (model != null) db.messageQueries.updateMessageModel(model, messageId)
        }
    }

    override suspend fun updateTitle(sessionId: String, title: String) {
        withContext(ioDispatcher) { db.sessionQueries.updateSessionTitle(title, sessionId) }
    }

    override suspend fun updateModel(sessionId: String, model: String) {
        withContext(ioDispatcher) { db.sessionQueries.updateSessionModel(model, sessionId) }
    }

    override suspend fun setPinned(sessionId: String, pinned: Boolean) {
        withContext(ioDispatcher) { db.sessionQueries.updateSessionPinned(pinned, sessionId) }
    }

    override suspend fun updateContextSummary(sessionId: String, summary: String) {
        withContext(ioDispatcher) { db.sessionQueries.updateSessionContextSummary(summary, sessionId) }
    }

    override suspend fun forkSession(sessionId: String): ChatSession? = withContext(ioDispatcher) {
        // Mapa id viejo -> id nuevo, para arrastrar el overlay de media (imágenes y vídeos
        // son transitorios y viven fuera de SQLite, indexados por id de mensaje).
        val idRemap = mutableMapOf<String, String>()
        val forked = db.transactionWithResult {
            val src = db.sessionQueries.selectSessionById(sessionId).executeAsOneOrNull()
                ?: return@transactionWithResult null
            val now = Clock.System.now().toEpochMilliseconds()
            val forkId = newId()
            db.sessionQueries.insertSession(
                id = forkId,
                title = src.title,
                model = src.model,
                created_at_epoch_ms = src.created_at_epoch_ms,
                // Se ordena por updated_at: `now` la deja arriba de su sección al crearse.
                updated_at_epoch_ms = now,
                pinned = false,
                generation_params = src.generation_params,
                context_summary = src.context_summary
            )
            val messages = db.messageQueries.selectMessagesBySession(sessionId).executeAsList()
            messages.forEach { m ->
                val copyId = newId()
                idRemap[m.id] = copyId
                db.messageQueries.insertMessage(
                    id = copyId,
                    session_id = forkId,
                    role = m.role,
                    content = m.content,
                    timestamp_epoch_ms = m.timestamp_epoch_ms,
                    // Mismo orden relativo: la copia se lee igual que el original.
                    sort_order = m.sort_order,
                    attachments = m.attachments,
                    tool_calls = m.tool_calls,
                    tool_call_id = m.tool_call_id,
                    tool_name = m.tool_name,
                    sources = m.sources,
                    reasoning = m.reasoning,
                    metrics = m.metrics,
                    checkpoint_id = null,
                    model = m.model
                )
            }
            val domainMessages = messages.map { m ->
                val (image, video) = mediaOverlay.value[m.id] ?: (null to null)
                m.toDomain(image, video).copy(id = idRemap.getValue(m.id), checkpointId = null)
            }
            db.sessionQueries.selectSessionById(forkId).executeAsOne().toDomain(domainMessages)
        }
        if (forked != null && idRemap.isNotEmpty()) {
            mediaOverlay.update { overlay ->
                overlay + idRemap.mapNotNull { (oldId, newIdValue) ->
                    overlay[oldId]?.let { newIdValue to it }
                }
            }
        }
        forked
    }

    override suspend fun deleteMessagesFrom(sessionId: String, messageId: String) {
        withContext(ioDispatcher) {
            db.transaction {
                val sortOrder = db.messageQueries.sortOrderForMessage(messageId).executeAsOneOrNull()
                if (sortOrder != null) {
                    db.messageQueries.deleteMessagesFromSortOrder(sessionId, sortOrder)
                    db.sessionQueries.updateSessionTimestamp(Clock.System.now().toEpochMilliseconds(), sessionId)
                }
            }
        }
    }

    override suspend fun clearAll() {
        withContext(ioDispatcher) { db.sessionQueries.deleteAllSessions() }
        mediaOverlay.update { emptyMap() }
    }

    /** No-op: cada mutación SQLDelight ya es una transacción síncrona/inmediata, a diferencia
     *  del throttle de 250ms de la implementación legacy sobre `Settings`. Se conserva en la
     *  interfaz porque el shutdown hook de desktop la sigue llamando. */
    override fun flushPendingWrites() = Unit

    private suspend fun touchSession(sessionId: String, block: () -> Unit) {
        withContext(ioDispatcher) {
            db.transaction {
                block()
                db.sessionQueries.updateSessionTimestamp(Clock.System.now().toEpochMilliseconds(), sessionId)
            }
        }
    }
}

private fun DbSession.toDomain(messages: List<ChatMessage>): ChatSession = ChatSession(
    id = id,
    title = title,
    model = model,
    createdAtEpochMs = created_at_epoch_ms,
    updatedAtEpochMs = updated_at_epoch_ms,
    messages = messages,
    pinned = pinned,
    generationParams = generation_params,
    contextSummary = context_summary
)

/**
 * El recorte a [SessionSummary.PREVIEW_MAX_CHARS] se hace aquí y no en SQL: `substr` en
 * SQLite cuenta bytes/caracteres según el tipo y no es equivalente a `take` de Kotlin con
 * acentos o emoji, que en este chat abundan.
 */
private fun DbSessionSummary.toDomain(): SessionSummary = SessionSummary(
    id = id,
    title = title,
    model = model,
    createdAtEpochMs = created_at_epoch_ms,
    updatedAtEpochMs = updated_at_epoch_ms,
    pinned = pinned,
    lastMessagePreview = last_message_preview?.take(SessionSummary.PREVIEW_MAX_CHARS)
)

private fun DbMessage.toDomain(imageDataUrl: String?, videoDataUrl: String?): ChatMessage = ChatMessage(
    id = id,
    role = role,
    content = content,
    timestampEpochMs = timestamp_epoch_ms,
    imageDataUrl = imageDataUrl,
    videoDataUrl = videoDataUrl,
    attachments = attachments,
    toolCalls = tool_calls,
    toolCallId = tool_call_id,
    toolName = tool_name,
    sources = sources,
    reasoning = reasoning,
    metrics = metrics,
    checkpointId = checkpoint_id,
    model = model
)
