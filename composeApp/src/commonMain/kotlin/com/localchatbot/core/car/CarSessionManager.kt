package com.localchatbot.core.car

import com.localchatbot.core.util.newId
import com.localchatbot.core.voice.markdownToSpeech
import com.localchatbot.domain.model.ChatRequestOverrides
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.datetime.Clock

/**
 * Sesión de chat dedicada al modo coche (Android Auto / CarPlay).
 *
 * Reutiliza [SendMessageUseCase] con un perfil restringido:
 * - System prompt propio: respuestas de 1–2 frases, sin markdown ni listas.
 * - `max_tokens` bajo (la respuesta se lee por TTS; largo = peligroso al volante).
 * - Solo `search_web` como tool — imágenes, diagramas, filesystem y shell no
 *   tienen sentido en el coche.
 *
 * No streamea token a token: espera la respuesta completa y la publica en
 * [CarMessageStore] ya convertida a texto hablable, lista para que la capa
 * de plataforma la entregue como mensaje del sistema.
 */
class CarSessionManager(
    private val chats: ChatRepository,
    private val sendMessage: SendMessageUseCase,
    private val createSession: CreateSessionUseCase,
    private val store: CarMessageStore
) {
    private var sessionId: String? = null

    /**
     * Activa el modo coche desde la UI del teléfono: publica un saludo en el
     * store para que exista la primera notificación de mensajería — sin ella
     * Android Auto no muestra la conversación y el usuario no puede responder.
     */
    fun startCarMode() {
        store.publish(
            CarMessage(
                id = newId(),
                text = GREETING,
                timestampEpochMs = Clock.System.now().toEpochMilliseconds()
            )
        )
    }

    /**
     * Procesa un texto dictado por el usuario en el coche y devuelve la
     * respuesta del asistente. En caso de error devuelve failure con un
     * mensaje pensado para leerse por voz — la capa de plataforma NUNCA
     * debe fallar en silencio (requisito de Fase 4 del plan).
     */
    suspend fun handleUserUtterance(text: String): Result<CarMessage> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Mensaje vacío"))

        val sid = ensureSession()
            ?: return Result.failure(IllegalStateException(SPOKEN_CONNECTION_ERROR))

        val result = sendMessage(
            sessionId = sid,
            text = text,
            imageDataUrl = null,
            overrides = CAR_OVERRIDES
        )
        if (result.isFailure) {
            return Result.failure(IllegalStateException(SPOKEN_CONNECTION_ERROR))
        }

        val answer = chats.getSession(sid)?.messages
            ?.lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
            ?.content
            ?: return Result.failure(IllegalStateException(SPOKEN_EMPTY_ERROR))

        val message = CarMessage(
            id = newId(),
            text = markdownToSpeech(answer),
            timestampEpochMs = Clock.System.now().toEpochMilliseconds()
        )
        store.publish(message)
        return Result.success(message)
    }

    /**
     * Crea (o reutiliza) la sesión "Modo coche". El título se fija en la
     * creación para que SendMessageUseCase no dispare la generación de título
     * en background (innecesaria: la sesión no se renombra nunca).
     */
    private suspend fun ensureSession(): String? {
        sessionId?.let { sid ->
            if (chats.getSession(sid) != null) return sid
        }
        return runCatching {
            val session = createSession()
            chats.updateTitle(session.id, CAR_SESSION_TITLE)
            sessionId = session.id
            session.id
        }.getOrNull()
    }

    companion object {
        const val CAR_SESSION_TITLE = "Modo coche"

        const val GREETING =
            "Modo coche activado. Responde a este mensaje desde el coche para hablar conmigo."

        /** Mensajes de error pensados para TTS, no para pantalla. */
        const val SPOKEN_CONNECTION_ERROR =
            "No puedo conectar con tu servidor. Comprueba la cobertura y la VPN."
        const val SPOKEN_EMPTY_ERROR =
            "El modelo no devolvió respuesta. Inténtalo de nuevo."

        /** Tope bajo: 1–2 frases habladas ≈ 60–80 tokens; margen para idiomas verbosos. */
        const val CAR_MAX_TOKENS = 160

        val CAR_OVERRIDES = ChatRequestOverrides(
            systemPrompt = buildString {
                append("Eres un asistente de voz en un coche. El usuario conduce y tu respuesta ")
                append("se lee en voz alta por el sistema del coche.\n\n")
                append("REGLAS ESTRICTAS:\n")
                append("1. Responde en 1 o 2 frases cortas. Nunca más de 2.\n")
                append("2. Texto plano hablable: sin markdown, sin listas, sin código, sin URLs, ")
                append("sin emojis, sin tablas.\n")
                append("3. Si la pregunta necesita información actual (noticias, tráfico, precios, ")
                append("tiempo), llama a la tool `search_web` y resume el resultado en una frase.\n")
                append("4. Si no sabes algo, dilo en una frase. No especules largo.\n")
                append("5. Responde siempre en el idioma del usuario.")
            },
            maxTokens = CAR_MAX_TOKENS,
            allowedToolNames = setOf("search_web")
        )
    }
}
