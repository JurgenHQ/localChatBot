package com.localchatbot.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * TTS de desktop que delega en el motor de voz nativo del SO mediante un proceso:
 *  - macOS: `say` (lee el texto por stdin).
 *  - Windows: PowerShell + `System.Speech.Synthesis` (texto por stdin).
 *  - Linux: `spd-say -w` o `espeak` si están instalados.
 *
 * [speak] suspende hasta que termina la lectura (o hasta [stop]). El [languageTag]
 * se ignora: se usa la voz por defecto del sistema.
 */
actual class TextToSpeech actual constructor() {
    private val _isSpeaking = MutableStateFlow(false)
    actual val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val isMac = os.contains("mac")
    private val isWindows = os.contains("win")

    @Volatile
    private var process: Process? = null

    actual suspend fun speak(text: String, languageTag: String) {
        val clipped = text.take(MAX_CHARS)
        if (clipped.isBlank()) return
        stop() // corta cualquier lectura en curso
        withContext(Dispatchers.IO) {
            runCatching {
                val proc = buildProcess(clipped) ?: return@runCatching
                process = proc
                _isSpeaking.value = true
                proc.waitFor()
            }
            process = null
            _isSpeaking.value = false
        }
    }

    private fun buildProcess(text: String): Process? = when {
        isMac -> {
            // `say -r <palabras/min>` controla la velocidad; sin mensaje lee de stdin.
            ProcessBuilder("say", "-r", RATE_WPM.toString()).start().also { feedStdin(it, text) }
        }
        isWindows -> {
            val script = buildString {
                append("Add-Type -AssemblyName System.Speech;")
                append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;")
                append("\$s.Rate = $WINDOWS_RATE;") // -10 (lento) a 10 (rápido), 0 = normal
                append("\$t = [Console]::In.ReadToEnd();")
                append("\$s.Speak(\$t)")
            }
            ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .start().also { feedStdin(it, text) }
        }
        else -> {
            when (firstAvailable(listOf("spd-say", "espeak"))) {
                // spd-say: -r -100..100. espeak: -s palabras/min.
                "spd-say" -> ProcessBuilder("spd-say", "-w", "-r", SPD_SAY_RATE.toString(), text).start()
                "espeak" -> ProcessBuilder("espeak", "-s", RATE_WPM.toString(), text).start()
                else -> null
            }
        }
    }

    private fun feedStdin(proc: Process, text: String) {
        runCatching {
            proc.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun firstAvailable(cmds: List<String>): String? = cmds.firstOrNull { cmd ->
        runCatching { ProcessBuilder("which", cmd).start().waitFor() == 0 }.getOrDefault(false)
    }

    actual fun stop() {
        process?.destroy()
        process = null
        _isSpeaking.value = false
    }

    actual fun close() = stop()

    private companion object {
        // Cap razonable: evita bloquear el pipe de stdin y lecturas eternas.
        const val MAX_CHARS = 5000

        // ─── Velocidad de lectura — sube estos números para que hable más rápido ───
        /** macOS `say` y Linux `espeak`: palabras por minuto (default del SO ~175-200). */
        const val RATE_WPM = 235
        /** Windows `SpeechSynthesizer.Rate`: -10 (lento) … 0 (normal) … 10 (rápido). */
        const val WINDOWS_RATE = 3
        /** Linux `spd-say`: -100 (lento) … 0 (normal) … 100 (rápido). */
        const val SPD_SAY_RATE = 30
    }
}
