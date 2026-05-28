package com.localchatbot.core.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import com.localchatbot.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class SpeechRecognizer actual constructor() {
    private val _state = MutableStateFlow<RecognizerState>(RecognizerState.Idle)
    actual val state: StateFlow<RecognizerState> = _state.asStateFlow()

    private var impl: AndroidSpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    actual suspend fun start(languageTag: String) {
        runOnMain {
            ensureImpl()
            _state.value = RecognizerState.Listening
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            runCatching { impl?.startListening(intent) }
                .onFailure { _state.value = RecognizerState.Error(it.message ?: "start failed") }
        }
    }

    actual fun stop() {
        runOnMain { runCatching { impl?.stopListening() } }
    }

    actual fun cancel() {
        runOnMain {
            runCatching { impl?.cancel() }
            _state.value = RecognizerState.Idle
        }
    }

    actual fun close() {
        runOnMain {
            runCatching { impl?.destroy() }
            impl = null
        }
    }

    private fun ensureImpl() {
        if (impl != null) return
        val ctx = AppContextHolder.context
        if (!AndroidSpeechRecognizer.isRecognitionAvailable(ctx)) {
            _state.value = RecognizerState.Error("Reconocimiento de voz no disponible en este dispositivo")
            return
        }
        impl = AndroidSpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(Listener())
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = RecognizerState.Listening
        }

        override fun onBeginningOfSpeech() {
            _state.value = RecognizerState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val permission = error == AndroidSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            // Errores transitorios habituales que no merecen romper el bucle.
            val recoverable = error == AndroidSpeechRecognizer.ERROR_NO_MATCH ||
                error == AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (recoverable) {
                _state.value = RecognizerState.Final("")
            } else {
                _state.value = RecognizerState.Error(describe(error), permissionDenied = permission)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            _state.value = RecognizerState.Final(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) _state.value = RecognizerState.Partial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        private fun describe(code: Int): String = when (code) {
            AndroidSpeechRecognizer.ERROR_AUDIO -> "Error de audio"
            AndroidSpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
            AndroidSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso de micrófono no concedido"
            AndroidSpeechRecognizer.ERROR_NETWORK -> "Error de red"
            AndroidSpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
            AndroidSpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció ninguna voz"
            AndroidSpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
            AndroidSpeechRecognizer.ERROR_SERVER -> "Error del servidor de reconocimiento"
            AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de escucha agotado"
            else -> "Error de reconocimiento ($code)"
        }
    }
}
