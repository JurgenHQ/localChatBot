package com.localchatbot.core.web

/**
 * Extrae el texto legible de un documento HTML.
 *
 * Implementación propia y a mano en vez de una librería: no existe un extractor de HTML
 * multiplataforma para KMP (jsoup es solo JVM, y este código tiene que compilar también
 * para iOS nativo). No pretende ser un parser de HTML correcto — pretende dejarle al modelo
 * el texto de un artículo sin ahogarlo en `<script>`, CSS y markup.
 *
 * El orden de las fases importa: primero se eliminan los bloques cuyo *contenido* no es
 * texto legible (script/style y compañía), y solo después se quitan las etiquetas sueltas.
 * Al revés, el cuerpo del `<script>` sobreviviría convertido en texto plano.
 */
object HtmlToText {

    /** Bloques cuyo contenido se descarta entero, no solo su etiqueta. */
    private val DROPPED_BLOCKS = listOf(
        "script", "style", "noscript", "template", "svg", "canvas", "iframe", "head"
    )

    /** Etiquetas que implican un salto de línea al desaparecer (si no, el texto se pega). */
    private val BLOCK_LEVEL = Regex(
        "</?(p|div|br|hr|li|ul|ol|tr|td|th|table|section|article|header|footer|nav|aside|" +
            "h[1-6]|blockquote|pre|figure|figcaption|form|main)[^>]*>",
        RegexOption.IGNORE_CASE
    )

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val ANY_TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
    private val MANY_BLANK_LINES = Regex("\\n{3,}")
    private val TRAILING_SPACES = Regex("[ \\t]+\\n")
    private val MANY_SPACES = Regex("[ \\t]{2,}")

    /**
     * Devuelve el texto legible de [html]. Si el contenido no parece HTML (no tiene ni una
     * etiqueta), se devuelve tal cual: sirve igual para JSON, Markdown o texto plano.
     */
    fun extract(html: String): String {
        if (!html.contains('<')) return html.trim()

        var text = html
        text = COMMENT.replace(text, " ")
        DROPPED_BLOCKS.forEach { tag ->
            // Tres casos, y el orden entre ellos es lo que hace que esto funcione:
            //
            // 1. Autocerrada (`<script src="a.js" />`, estilo XHTML). Va PRIMERO porque no
            //    tiene `</script>`: si la tratara como "sin cerrar" se comería el resto del
            //    documento, y esa forma aparece en páginas reales.
            text = Regex("<$tag\\b[^>]*/>", RegexOption.IGNORE_CASE).replace(text, " ")
            // 2. Par normal: se descarta el contenido, que es de lo que se trata.
            text = Regex(
                "<$tag\\b[^>]*>.*?</$tag\\s*>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).replace(text, " ")
            // 3. Abierta y nunca cerrada (HTML roto, o descarga cortada): se consume hasta
            //    el final. Quitar solo la etiqueta dejaría el cuerpo del script como texto
            //    plano en el resultado, que es peor: el modelo se comería el JavaScript.
            text = Regex(
                "<$tag\\b[^>]*>.*$",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).replace(text, " ")
        }
        text = BLOCK_LEVEL.replace(text, "\n")
        text = ANY_TAG.replace(text, " ")
        text = decodeEntities(text)

        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .let { MANY_SPACES.replace(it, " ") }
            .let { TRAILING_SPACES.replace(it, "\n") }
            .let { MANY_BLANK_LINES.replace(it, "\n\n") }
            .lines().joinToString("\n") { it.trim() }
            .let { MANY_BLANK_LINES.replace(it, "\n\n") }
            .trim()
    }

    /** Título del documento (`<title>`), o null. Se usa como cabecera del resultado. */
    fun extractTitle(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues?.get(1)
            ?.let { decodeEntities(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * `&amp;` se decodifica al final a propósito: hacerlo antes convertiría `&amp;lt;` en
     * `<`, reintroduciendo markup que ya habíamos quitado.
     */
    private fun decodeEntities(s: String): String {
        var out = s
        out = NUMERIC_ENTITY.replace(out) { m ->
            val isHex = m.groupValues[1].isNotEmpty()
            val code = m.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
            if (code != null && code in 1..0x10FFFF) {
                runCatching { code.toChar().toString() }.getOrDefault(m.value)
            } else {
                m.value
            }
        }
        NAMED_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
        return out.replace("&amp;", "&")
    }

    /**
     * No es la tabla HTML completa (son cientos): son las entidades que de verdad aparecen.
     * Las acentuadas no son opcionales — en cualquier página en español `&aacute;` y
     * `&ntilde;` salen constantemente, y sin decodificarlas el modelo lee "informaci&oacute;n".
     */
    private val NAMED_ENTITIES = listOf(
        "&nbsp;" to " ",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&hellip;" to "…",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&laquo;" to "«",
        "&raquo;" to "»",
        "&copy;" to "©",
        "&reg;" to "®",
        "&trade;" to "™",
        "&euro;" to "€",
        "&pound;" to "£",
        "&deg;" to "°",
        "&middot;" to "·",
        "&bull;" to "•",
        "&aacute;" to "á", "&eacute;" to "é", "&iacute;" to "í",
        "&oacute;" to "ó", "&uacute;" to "ú", "&ntilde;" to "ñ",
        "&Aacute;" to "Á", "&Eacute;" to "É", "&Iacute;" to "Í",
        "&Oacute;" to "Ó", "&Uacute;" to "Ú", "&Ntilde;" to "Ñ",
        "&uuml;" to "ü", "&Uuml;" to "Ü", "&iexcl;" to "¡", "&iquest;" to "¿",
        "&agrave;" to "à", "&egrave;" to "è", "&igrave;" to "ì",
        "&ograve;" to "ò", "&ugrave;" to "ù",
        "&acirc;" to "â", "&ecirc;" to "ê", "&icirc;" to "î",
        "&ocirc;" to "ô", "&ucirc;" to "û",
        "&auml;" to "ä", "&euml;" to "ë", "&iuml;" to "ï", "&ouml;" to "ö",
        "&Auml;" to "Ä", "&Ouml;" to "Ö", "&szlig;" to "ß",
        "&ccedil;" to "ç", "&Ccedil;" to "Ç", "&atilde;" to "ã", "&otilde;" to "õ",
        "&rsquo;" to "’", "&lsquo;" to "‘",
        "&rdquo;" to "”", "&ldquo;" to "“"
    )
}
