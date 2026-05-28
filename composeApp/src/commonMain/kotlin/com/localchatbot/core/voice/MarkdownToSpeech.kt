package com.localchatbot.core.voice

/**
 * Aplana Markdown a texto plano apto para TTS:
 * - Reemplaza bloques de código por la frase "bloque de código omitido".
 * - Quita marcadores inline (`*`, `_`, `~`, `` ` ``, `#`).
 * - Convierte `[texto](url)` en `texto`.
 * - Colapsa múltiples saltos de línea en pausa (punto).
 */
fun markdownToSpeech(input: String): String {
    if (input.isBlank()) return ""
    var text = input

    // Bloques de código vallados: ```lang\n...\n```
    text = Regex("```[\\s\\S]*?```").replace(text, " (bloque de código omitido) ")
    // Inline code: `algo`
    text = Regex("`([^`]*)`").replace(text) { it.groupValues[1] }
    // Imágenes: ![alt](url) -> alt
    text = Regex("!\\[([^\\]]*)]\\([^)]*\\)").replace(text) { it.groupValues[1] }
    // Enlaces: [texto](url) -> texto
    text = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(text) { it.groupValues[1] }
    // Encabezados al inicio de línea: ###, ##, #
    text = Regex("(?m)^\\s{0,3}#{1,6}\\s+").replace(text, "")
    // Listas con viñeta: "- ", "* ", "+ " al inicio de línea
    text = Regex("(?m)^\\s*[-*+]\\s+").replace(text, "")
    // Listas numeradas: "1. " al inicio de línea
    text = Regex("(?m)^\\s*\\d+\\.\\s+").replace(text, "")
    // Citas: "> "
    text = Regex("(?m)^\\s*>\\s+").replace(text, "")
    // Énfasis: **bold**, *italic*, __bold__, _italic_, ~~strike~~
    text = Regex("(\\*\\*|__)(.+?)\\1").replace(text) { it.groupValues[2] }
    text = Regex("(\\*|_)(.+?)\\1").replace(text) { it.groupValues[2] }
    text = Regex("~~(.+?)~~").replace(text) { it.groupValues[1] }
    // Reglas horizontales
    text = Regex("(?m)^\\s*([-*_])\\s*\\1\\s*\\1[\\s\\1]*$").replace(text, "")
    // Colapsar espacios y saltos de línea múltiples
    text = Regex("[\\t ]+").replace(text, " ")
    text = Regex("\\n{2,}").replace(text, ". ")
    text = Regex("\\n").replace(text, " ")
    return text.trim()
}
