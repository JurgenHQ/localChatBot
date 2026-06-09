package com.localchatbot.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop (JVM) no expone una API de reconocimiento de voz estándar. Stub no-op
 * por ahora; ver Fase 4 del plan de desktop (Vosk / whisper.cpp).
 */
actual class SpeechRecognizer actual constructor() {
    private val _state = MutableStateFlow<RecognizerState>(RecognizerState.Idle)
    actual val state: StateFlow<RecognizerState> = _state

    actual suspend fun start(languageTag: String) {
        _state.value = RecognizerState.Error("Voz no disponible en desktop", permissionDenied = false)
    }

    actual fun stop() {}
    actual fun cancel() {}
    actual fun close() {}
}
