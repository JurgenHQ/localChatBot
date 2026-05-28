package com.localchatbot.core.voice

import android.os.Bundle
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.localchatbot.AppContextHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

actual class TextToSpeech actual constructor() {
    private val _isSpeaking = MutableStateFlow(false)
    actual val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: AndroidTts? = null
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

    init {
        tts = AndroidTts(AppContextHolder.context) { status ->
            ready.complete(status == AndroidTts.SUCCESS)
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }
                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    utteranceId?.let { pending.remove(it)?.complete(Unit) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    utteranceId?.let { pending.remove(it)?.complete(Unit) }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    utteranceId?.let { pending.remove(it)?.complete(Unit) }
                }
            })
        }
    }

    actual suspend fun speak(text: String, languageTag: String) {
        if (text.isBlank()) return
        val ok = ready.await()
        if (!ok) return
        val engine = tts ?: return
        selectBestVoice(engine, languageTag)
        val id = "u-${System.nanoTime()}"
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        val params = Bundle()
        val result = engine.speak(text, AndroidTts.QUEUE_FLUSH, params, id)
        if (result != AndroidTts.SUCCESS) {
            pending.remove(id)
            return
        }
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                runCatching { engine.stop() }
                pending.remove(id)
                _isSpeaking.value = false
            }
            // Cuando deferred se complete, resumimos.
            deferred.invokeOnCompletion { cont.resume(Unit) }
        }
    }

    /**
     * Selecciona la voz de mayor calidad disponible para el idioma, evitando
     * voces que requieran red o que no estén instaladas. Si no encuentra
     * ninguna apta, recae en fijar solo el idioma (voz por defecto del motor).
     */
    private fun selectBestVoice(engine: AndroidTts, languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        val best = runCatching {
            engine.voices
                ?.asSequence()
                ?.filter { v ->
                    v.locale.language == locale.language &&
                        !v.isNetworkConnectionRequired &&
                        v.features?.contains(AndroidTts.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                ?.sortedWith(
                    compareByDescending<Voice> { it.locale.country == locale.country }
                        .thenByDescending { it.quality }
                )
                ?.firstOrNull()
        }.getOrNull()
        if (best != null) {
            engine.voice = best
        } else {
            engine.language = locale
        }
    }

    actual fun stop() {
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        pending.values.toList().forEach { it.complete(Unit) }
        pending.clear()
    }

    actual fun close() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
