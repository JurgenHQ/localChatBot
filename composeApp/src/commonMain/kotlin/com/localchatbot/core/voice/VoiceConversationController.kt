package com.localchatbot.core.voice

import com.localchatbot.core.background.BackgroundExecutor
import com.localchatbot.core.network.friendlyStreamErrorMessage
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

sealed interface VoiceMode {
    data object Off : VoiceMode
    data object RequestingPermission : VoiceMode
    data class Listening(val partial: String = "") : VoiceMode
    data class Thinking(val userText: String) : VoiceMode
    data class Speaking(val answer: String) : VoiceMode
    data class Error(val message: String) : VoiceMode
}

/**
 * Orquesta el modo conversación manos libres:
 *   Listening → Thinking → Speaking → Listening → …
 *
 * Mientras hay TTS activo, el recognizer permanece detenido para evitar
 * realimentación (el micro escuchando al altavoz).
 */
class VoiceConversationController(
    private val recognizer: SpeechRecognizer,
    private val tts: TextToSpeech,
    private val chatRepository: ChatRepository,
    private val preferences: PreferencesRepository,
    private val activeSessionStore: ActiveSessionStore,
    private val streamingStateStore: StreamingStateStore,
    private val sendMessage: SendMessageUseCase,
    private val createSession: CreateSessionUseCase,
    private val backgroundExecutor: BackgroundExecutor,
    private val applicationScope: CoroutineScope
) {
    private val _mode = MutableStateFlow<VoiceMode>(VoiceMode.Off)
    val mode: StateFlow<VoiceMode> = _mode.asStateFlow()

    private var loopJob: Job? = null

    val isActive: Boolean get() = _mode.value !is VoiceMode.Off

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = applicationScope.launch {
            try {
                _mode.value = VoiceMode.RequestingPermission
                if (!requestVoicePermissions()) {
                    _mode.value = VoiceMode.Error("Permiso de micrófono denegado")
                    return@launch
                }
                backgroundExecutor.start("voice-conversation")
                loop()
            } finally {
                backgroundExecutor.stop()
                runCatching { recognizer.cancel() }
                runCatching { tts.stop() }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { recognizer.cancel() }
        runCatching { tts.stop() }
        _mode.value = VoiceMode.Off
    }

    /**
     * Fuerza el fin de la escucha actual: el reconocedor entrega lo captado como
     * resultado final y el bucle lo envía al modelo. Sin efecto fuera de Listening.
     */
    fun submitNow() {
        if (_mode.value is VoiceMode.Listening) {
            runCatching { recognizer.stop() }
        }
    }

    private suspend fun loop() {
        val lang = DEFAULT_LANGUAGE_TAG
        while (true) {
            // 1) Escuchar al usuario
            _mode.value = VoiceMode.Listening("")
            recognizer.start(lang)
            val userText = waitForRecognitionResult()
            if (userText.isNullOrBlank()) continue

            // 2) Asegurar sesión activa y enviar
            _mode.value = VoiceMode.Thinking(userText)
            val sessionId = activeSessionStore.activeSessionId.value
                ?: createSession().also { activeSessionStore.set(it.id) }.id

            // Feedback inmediato: una frase corta mientras el modelo trabaja.
            val ackJob = applicationScope.launch {
                runCatching { tts.speak(THINKING_PHRASES.random(), lang) }
            }

            streamingStateStore.start(sessionId)
            val sendResult = runCatching {
                sendMessage(sessionId, userText, null)
            }
            streamingStateStore.stop(sessionId)

            // No pisar la frase de feedback con la respuesta real.
            ackJob.join()

            val failure = sendResult.exceptionOrNull()
                ?: sendResult.getOrNull()?.exceptionOrNull()
            if (failure != null) {
                val msg = friendlyStreamErrorMessage(failure)
                _mode.value = VoiceMode.Speaking(msg)
                runCatching { tts.speak(msg, lang) }
                continue
            }

            // 3) Tomar el último mensaje del assistant y reproducirlo
            val answer = lastAssistantText(sessionId)
            if (answer.isNullOrBlank()) continue
            val spoken = markdownToSpeech(answer)
            if (spoken.isBlank()) continue
            _mode.value = VoiceMode.Speaking(spoken)
            runCatching { tts.speak(spoken, lang) }
            // Vuelve al inicio del bucle → Listening
        }
    }

    /** Espera hasta que el recognizer emita Final/Error. Va publicando parciales en `_mode`. */
    private suspend fun waitForRecognitionResult(): String? {
        val terminal = recognizer.state.first { s ->
            if (s is RecognizerState.Partial) {
                _mode.value = VoiceMode.Listening(s.text)
            }
            s is RecognizerState.Final || s is RecognizerState.Error
        }
        return when (terminal) {
            is RecognizerState.Final -> terminal.text
            is RecognizerState.Error -> {
                _mode.value = VoiceMode.Error(terminal.message)
                null
            }
            else -> null
        }
    }

    private suspend fun lastAssistantText(sessionId: String): String? {
        // Lectura puntual por id: no hace falta un flow ni el resto del historial.
        val session = chatRepository.getSession(sessionId) ?: return null
        return session.messages.lastOrNull { it.role == Role.Assistant }?.content
    }

    private companion object {
        const val DEFAULT_LANGUAGE_TAG = "es-ES"

        val THINKING_PHRASES = listOf(
            "Un momento, lo estoy viendo.",
            "Claro, déjame revisarlo.",
            "Vale, lo estoy procesando.",
            "Dame un segundo y te digo.",
            "Enseguida te respondo.",
            "Permíteme un momento."
        )
    }
}
