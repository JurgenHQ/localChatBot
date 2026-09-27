package com.localchatbot.domain.export

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.Role

/**
 * Serialización de una conversación a Markdown, para copiarla al portapapeles o guardarla
 * en un archivo.
 *
 * Funciones **puras** (sin I/O, sin fecha del sistema: el timestamp entra por parámetro),
 * para poder testearlas sueltas — no hay tests automatizados en el repo.
 *
 * Qué entra y qué no:
 * - Los mensajes `Tool` **no** se vuelcan: son payloads JSON de cientos de líneas que no le
 *   dicen nada a quien lee la conversación después. En su lugar, cada tool que el asistente
 *   invocó aparece como una línea `🔧 nombre` bajo su mensaje.
 * - Las imágenes y videos generados tampoco: viven fuera de la base (base64 transitorio) y
 *   pegarlos como data URL de megas en un .md lo haría inservible. Se marcan con una nota.
 * - Los adjuntos del usuario se listan por nombre; su contenido ya está en el mensaje que
 *   se le mandó al modelo, no en la burbuja, y volcarlos duplicaría archivos enteros.
 */
object ChatExport {

    /** Conversación completa en Markdown. [exportedAt] es texto ya formateado (o null). */
    fun sessionToMarkdown(session: ChatSession, exportedAt: String? = null): String = buildString {
        append("# ")
        appendLine(session.title.ifBlank { "Conversación" })
        appendLine()
        val meta = buildList {
            if (session.model.isNotBlank()) add("Modelo: `${session.model}`")
            add("Mensajes: ${session.messages.count { it.role != Role.Tool }}")
            if (exportedAt != null) add("Exportado: $exportedAt")
        }
        meta.forEach { appendLine("- $it") }
        if (!session.contextSummary.isNullOrBlank()) {
            appendLine()
            appendLine("> **Resumen del historial anterior** (contexto compactado)")
            // Prefijo `> ` línea a línea: un blockquote de varias líneas sin el prefijo se
            // rompe en el primer salto y el resto queda como texto suelto.
            session.contextSummary.trim().lines().forEach { appendLine("> $it") }
        }
        appendLine()
        appendMessages(session.messages)
    }

    /**
     * Un turno suelto: el mensaje de usuario [messageId] y todo lo que vino después hasta
     * el siguiente mensaje de usuario (la respuesta del asistente y sus tools).
     *
     * Si [messageId] es un mensaje del asistente, exporta ese mensaje solo — es lo que
     * significa "copiar esta respuesta". Null si el id no existe.
     */
    fun turnToMarkdown(messages: List<ChatMessage>, messageId: String): String? {
        val start = messages.indexOfFirst { it.id == messageId }
        if (start < 0) return null
        val slice = if (messages[start].role == Role.User) {
            val next = messages.drop(start + 1).indexOfFirst { it.role == Role.User }
            if (next < 0) messages.drop(start) else messages.subList(start, start + 1 + next)
        } else {
            listOf(messages[start])
        }
        return buildString { appendMessages(slice) }.trim()
    }

    private fun StringBuilder.appendMessages(messages: List<ChatMessage>) {
        for (msg in messages) {
            when (msg.role) {
                // Los resultados de tool ya están representados por la línea 🔧 del
                // mensaje que las invocó.
                Role.Tool -> continue
                Role.System -> continue
                Role.User -> {
                    appendLine("## 👤 Usuario")
                    appendLine()
                    appendBody(msg)
                }
                Role.Assistant -> {
                    appendLine("## 🤖 Asistente")
                    appendLine()
                    appendBody(msg)
                }
            }
        }
    }

    private fun StringBuilder.appendBody(msg: ChatMessage) {
        msg.attachments?.takeIf { it.isNotEmpty() }?.forEach { att ->
            appendLine("📎 `${att.name}`")
        }
        if (msg.imageDataUrl != null) appendLine("_[imagen]_")
        if (msg.videoDataUrl != null) appendLine("_[video]_")
        val body = msg.content.trim()
        if (body.isNotEmpty()) {
            appendLine(body)
        }
        msg.toolCalls?.takeIf { it.isNotEmpty() }?.forEach { call ->
            appendLine()
            appendLine("🔧 `${call.name}`")
        }
        msg.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
            appendLine()
            sources.forEach { src ->
                appendLine("- [${src.title.ifBlank { src.url }}](${src.url})")
            }
        }
        appendLine()
    }

    /**
     * Nombre de archivo sugerido a partir del título. Se limita a `[a-zA-Z0-9-_]` porque el
     * mismo nombre tiene que sobrevivir en NTFS (que rechaza `\ / : * ? " < > |`) y en APFS,
     * y un título de chat puede traer cualquier cosa, incluidos emojis.
     */
    fun suggestedFileName(title: String): String {
        val slug = title.trim().lowercase()
            .map { ch ->
                when {
                    ch.isLetterOrDigit() && ch.code < 128 -> ch
                    ch == '-' || ch == '_' -> ch
                    // Los títulos de este proyecto son en español: sin translitear,
                    // "configuración" quedaba como "configuraci-n".
                    else -> ACCENT_MAP[ch] ?: '-'
                }
            }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(60)
        return (slug.ifBlank { "conversacion" }) + ".md"
    }

    /** Se aplica sobre el título ya en minúsculas, así que no hace falta el caso mayúscula. */
    private val ACCENT_MAP: Map<Char, Char> = mapOf(
        'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'ã' to 'a',
        'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
        'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
        'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o', 'õ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
        'ñ' to 'n', 'ç' to 'c'
    )
}
