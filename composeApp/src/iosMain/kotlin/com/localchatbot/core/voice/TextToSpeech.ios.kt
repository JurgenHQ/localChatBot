package com.localchatbot.core.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCSignatureOverride

@OptIn(ExperimentalForeignApi::class)
actual class TextToSpeech actual constructor() {
    private val _isSpeaking = MutableStateFlow(false)
    actual val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val synthesizer = AVSpeechSynthesizer()
    private var current: CompletableDeferred<Unit>? = null

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didStartSpeechUtterance: AVSpeechUtterance
        ) {
            _isSpeaking.value = true
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance
        ) {
            _isSpeaking.value = false
            current?.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance
        ) {
            _isSpeaking.value = false
            current?.complete(Unit)
        }
    }

    init {
        synthesizer.delegate = delegate
    }

    actual suspend fun speak(text: String, languageTag: String) {
        if (text.isBlank()) return
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
        val utterance = AVSpeechUtterance(string = text)
        bestVoice(languageTag)?.let { utterance.voice = it }
        val deferred = CompletableDeferred<Unit>()
        current = deferred
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
                _isSpeaking.value = false
            }
            deferred.invokeOnCompletion { cont.resume(Unit) }
            synthesizer.speakUtterance(utterance)
        }
    }

    actual fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        _isSpeaking.value = false
        current?.complete(Unit)
    }

    actual fun close() {
        stop()
    }

    /**
     * Elige la voz de mayor calidad para el idioma: prioriza Premium > Enhanced >
     * Default y, a igual calidad, la que coincide exactamente con el languageTag.
     * Las voces Premium/Enhanced deben estar descargadas por el usuario en
     * Ajustes → Accesibilidad → Contenido leído; si no, recae en la estándar.
     */
    private fun bestVoice(languageTag: String): AVSpeechSynthesisVoice? {
        val langPrefix = languageTag.substringBefore('-').lowercase()
        val candidates = AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.lowercase().startsWith(langPrefix) }
        if (candidates.isEmpty()) return AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
        return candidates.maxByOrNull { voice ->
            val quality = when (voice.quality) {
                AVSpeechSynthesisVoiceQualityPremium -> 3
                AVSpeechSynthesisVoiceQualityEnhanced -> 2
                else -> 1
            }
            val exact = if (voice.language.equals(languageTag, ignoreCase = true)) 1 else 0
            quality * 2 + exact
        }
    }
}
