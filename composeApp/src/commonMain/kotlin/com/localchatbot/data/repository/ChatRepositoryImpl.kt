package com.localchatbot.data.repository

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.WebSource
import com.localchatbot.domain.repository.ChatRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.random.Random

class ChatRepositoryImpl(
    private val settings: Settings,
    private val json: Json
) : ChatRepository {

    private val _sessions = MutableStateFlow(load())
    override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /**
     * Scope dedicado a la persistencia. Cada `mutate` actualiza el StateFlow al
     * instante (UI reactiva) pero programa la escritura a disco con un throttle
     * de [PERSIST_THROTTLE_MS]. Sin esto, durante un stream con sesiones que
     * contienen imágenes base64 se serializaría toda la lista en cada delta,
     * lo que bloquea por completo el thread donde corra (incluso si no es Main,
     * desperdicia CPU y memoria masivamente).
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingPersist: Job? = null

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
        mutate { it + session }
        return session
    }

    override suspend fun deleteSession(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    override suspend fun getSession(id: String): ChatSession? =
        _sessions.value.firstOrNull { it.id == id }

    override suspend fun appendMessage(sessionId: String, message: ChatMessage) {
        mutate { list ->
            list.map { s ->
                if (s.id == sessionId) {
                    s.copy(
                        messages = s.messages + message,
                        updatedAtEpochMs = message.timestampEpochMs
                    )
                } else s
            }
        }
    }

    override suspend fun updateMessageContent(sessionId: String, messageId: String, content: String) {
        mutate { list ->
            list.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    messages = s.messages.map { m ->
                        if (m.id == messageId) m.copy(content = content) else m
                    },
                    updatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
    }

    override suspend fun updateMessageToolCalls(
        sessionId: String,
        messageId: String,
        toolCalls: List<PersistedToolCall>
    ) {
        mutate { list ->
            list.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    messages = s.messages.map { m ->
                        if (m.id == messageId) m.copy(toolCalls = toolCalls) else m
                    },
                    updatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
    }

    override suspend fun updateMessageSources(
        sessionId: String,
        messageId: String,
        sources: List<WebSource>
    ) {
        mutate { list ->
            list.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    messages = s.messages.map { m ->
                        if (m.id == messageId) m.copy(sources = sources) else m
                    },
                    updatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
    }

    override suspend fun updateMessageImage(sessionId: String, messageId: String, imageDataUrl: String) {
        mutate { list ->
            list.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    messages = s.messages.map { m ->
                        if (m.id == messageId) m.copy(imageDataUrl = imageDataUrl) else m
                    },
                    updatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
    }

    override suspend fun updateTitle(sessionId: String, title: String) {
        mutate { list ->
            list.map { s -> if (s.id == sessionId) s.copy(title = title) else s }
        }
    }

    override suspend fun setPinned(sessionId: String, pinned: Boolean) {
        mutate { list ->
            list.map { s -> if (s.id == sessionId) s.copy(pinned = pinned) else s }
        }
    }

    override suspend fun deleteMessagesFrom(sessionId: String, messageId: String) {
        mutate { list ->
            list.map { s ->
                if (s.id != sessionId) s
                else {
                    val idx = s.messages.indexOfFirst { it.id == messageId }
                    if (idx < 0) s
                    else s.copy(
                        messages = s.messages.subList(0, idx),
                        updatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
                    )
                }
            }
        }
    }

    override suspend fun clearAll() {
        // Cancela cualquier persistencia pendiente y limpia inmediatamente.
        pendingPersist?.cancel()
        settings.remove(KEY_SESSIONS)
        _sessions.value = emptyList()
    }

    private fun mutate(transform: (List<ChatSession>) -> List<ChatSession>) {
        // Las sesiones fijadas siempre van arriba, ordenadas por updatedAt desc;
        // el resto debajo, también por updatedAt desc.
        val next = transform(_sessions.value)
            .sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAtEpochMs })
        _sessions.value = next
        schedulePersist()
    }

    /**
     * Throttle: la primera mutación tras un periodo de calma programa una
     * persistencia [PERSIST_THROTTLE_MS] después. Mientras esa escritura
     * está pendiente, las nuevas mutaciones NO la cancelan — al disparar,
     * `_sessions.value` ya contiene el estado más reciente. Resultado: como
     * mucho se escribe a disco 4 veces por segundo (en lugar de potencialmente
     * 30+ veces durante un stream), pero nunca se queda atrás de la UI más
     * de [PERSIST_THROTTLE_MS] ms.
     */
    private fun schedulePersist() {
        if (pendingPersist?.isActive == true) return
        pendingPersist = persistScope.launch {
            delay(PERSIST_THROTTLE_MS)
            persist(_sessions.value)
        }
    }

    private fun persist(list: List<ChatSession>) {
        // imageDataUrl son base64 de varios cientos de KB. Persistirlos haría que
        // plataformas con límite de tamaño por clave (Java Preferences: 8 KB) fallen
        // silenciosamente. Las imágenes son transitorias — si el usuario las necesita
        // las guarda explícitamente con "Guardar imagen".
        val stripped = list.map { session ->
            session.copy(messages = session.messages.map { msg ->
                if (msg.imageDataUrl != null) msg.copy(imageDataUrl = null) else msg
            })
        }
        runCatching {
            val raw = json.encodeToString(SessionsSerializer, stripped)
            settings.putString(KEY_SESSIONS, raw)
        }
    }

    private fun load(): List<ChatSession> {
        val raw = settings.getStringOrNull(KEY_SESSIONS) ?: return emptyList()
        return runCatching { json.decodeFromString(SessionsSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun newId(): String =
        Clock.System.now().toEpochMilliseconds().toString(36) +
            "-" + Random.nextInt(0, 1_000_000).toString(36)

    private companion object {
        const val KEY_SESSIONS = "chat_sessions"
        const val PERSIST_THROTTLE_MS = 250L
        val SessionsSerializer = kotlinx.serialization.builtins.ListSerializer(ChatSession.serializer())
    }
}
