package com.localchatbot.core.voice

import kotlinx.coroutines.flow.StateFlow

sealed interface RecognizerState {
    data object Idle : RecognizerState
    data object Listening : RecognizerState
    data class Partial(val text: String) : RecognizerState
    data class Final(val text: String) : RecognizerState
    data class Error(val message: String, val permissionDenied: Boolean = false) : RecognizerState
}

expect class SpeechRecognizer() {
    val state: StateFlow<RecognizerState>

    /** Inicia el reconocimiento. languageTag estilo BCP-47 ("es-ES", "en-US"). */
    suspend fun start(languageTag: String)

    /** Termina la escucha y entrega el resultado parcial como Final. */
    fun stop()

    /** Cancela sin entregar resultado. */
    fun cancel()

    /** Libera recursos. */
    fun close()
}
