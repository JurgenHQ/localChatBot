package com.localchatbot.core.voice

import kotlinx.coroutines.flow.StateFlow

expect class TextToSpeech() {
    val isSpeaking: StateFlow<Boolean>

    /** Habla el texto y suspende hasta terminar (o cancelarse). */
    suspend fun speak(text: String, languageTag: String)

    fun stop()

    fun close()
}
