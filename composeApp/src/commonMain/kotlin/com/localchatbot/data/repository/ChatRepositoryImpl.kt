package com.localchatbot.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.localchatbot.core.util.newId
import com.localchatbot.data.local.db.LocalChatBotDatabase
import com.localchatbot.data.local.db.Message as DbMessage
import com.localchatbot.data.local.db.Session as DbSession
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.TokenMetrics
import com.localchatbot.domain.model.WebSource
import com.localchatbot.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

    override val sessions: Flow<List<ChatSession>> = combine(
        db.sessionQueries.selectAllSessions().asFlow().mapToList(ioDispatcher),
        db.messageQueries.selectAllMessages().asFlow().mapToList(ioDispatcher),
        mediaOverlay
    ) { dbSessions, dbMessages, overlay ->
        val messagesBySession = dbMessages.groupBy { it.session_id }
        dbSessions.map { s ->
            val messages = messagesBySession[s.id].orEmpty().map { m ->
                val (image, video) = overlay[m.id] ?: (null to null)
                m.toDomain(image, video)
            }
            s.toDomain(messages)
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
                    checkpoint_id = message.checkpointId
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

    override suspend fun updateMessageMetrics(sessionId: String, messageId: String, metrics: TokenMetrics) {
        touchSession(sessionId) { db.messageQueries.updateMessageMetrics(metrics, messageId) }
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
    checkpointId = checkpoint_id
)
