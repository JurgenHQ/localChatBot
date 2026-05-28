package com.localchatbot.core.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
actual class SpeechRecognizer actual constructor() {
    private val _state = MutableStateFlow<RecognizerState>(RecognizerState.Idle)
    actual val state: StateFlow<RecognizerState> = _state.asStateFlow()

    private val audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    actual suspend fun start(languageTag: String) {
        cleanup()
        val rec = SFSpeechRecognizer(NSLocale(localeIdentifier = languageTag))
        if (rec == null || !rec.available) {
            _state.value = RecognizerState.Error("Reconocimiento de voz no disponible")
            return
        }
        recognizer = rec

        val session = AVAudioSession.sharedInstance()
        runCatching {
            session.setCategory(AVAudioSessionCategoryRecord, error = null)
            session.setActive(true, error = null)
        }.onFailure {
            _state.value = RecognizerState.Error("No se pudo activar el audio")
            return
        }

        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req

        val inputNode = audioEngine.inputNode
        val format = inputNode.outputFormatForBus(0u)
        inputNode.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            buffer?.let { req.appendAudioPCMBuffer(it) }
        }
        audioEngine.prepare()
        runCatching { audioEngine.startAndReturnError(null) }
            .onFailure {
                _state.value = RecognizerState.Error("No se pudo iniciar el micrófono")
                return
            }

        _state.value = RecognizerState.Listening
        task = rec.recognitionTaskWithRequest(req) { result: SFSpeechRecognitionResult?, error ->
            if (result != null) {
                val text = result.bestTranscription.formattedString
                if (result.isFinal()) {
                    _state.value = RecognizerState.Final(text)
                    stopAudio()
                } else if (text.isNotEmpty()) {
                    _state.value = RecognizerState.Partial(text)
                }
            }
            if (error != null) {
                // Si ya hubo un Final, ignoramos el error de cierre.
                if (_state.value !is RecognizerState.Final) {
                    _state.value = RecognizerState.Final("")
                }
                stopAudio()
            }
        }
    }

    actual fun stop() {
        request?.endAudio()
        stopAudio()
    }

    actual fun cancel() {
        task?.cancel()
        cleanup()
        _state.value = RecognizerState.Idle
    }

    actual fun close() {
        cleanup()
    }

    private fun stopAudio() {
        if (audioEngine.running) {
            audioEngine.stop()
            audioEngine.inputNode.removeTapOnBus(0u)
        }
        runCatching {
            AVAudioSession.sharedInstance().setActive(false, error = null)
        }
    }

    private fun cleanup() {
        stopAudio()
        request?.endAudio()
        task?.finish()
        task = null
        request = null
    }
}
