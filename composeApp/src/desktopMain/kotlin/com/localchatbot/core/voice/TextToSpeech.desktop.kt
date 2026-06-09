package com.localchatbot.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stub no-op para desktop. Ver Fase 4 del plan (FreeTTS / `say` / SAPI).
 */
actual class TextToSpeech actual constructor() {
    private val _isSpeaking = MutableStateFlow(false)
    actual val isSpeaking: StateFlow<Boolean> = _isSpeaking

    actual suspend fun speak(text: String, languageTag: String) {}
    actual fun stop() {}
    actual fun close() {}
}
