package com.localchatbot.domain.search

import com.localchatbot.domain.model.Role

/** Una coincidencia de la búsqueda global, ya resuelta a la conversación que la contiene. */
data class MessageSearchResult(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: Role,
    val timestampEpochMs: Long,
    /** Fragmento con la coincidencia, listo para [parseSnippet]. */
    val snippet: String
)

/** Trozo de un fragmento: [isMatch] marca lo que coincidió, para resaltarlo. */
data class SnippetSegment(val text: String, val isMatch: Boolean)

/** Delimitadores que emite `snippet()` en la consulta `searchMessages` (`char(2)`/`char(3)`). */
private const val MATCH_START = '\u0002'
private const val MATCH_END = '\u0003'

/** Por debajo de esto no se busca: una letra suelta devuelve medio historial. */
const val MIN_SEARCH_QUERY_LENGTH = 2

/**
 * Traduce lo que escribe el usuario a una expresión `MATCH` de FTS5.
 *
 * Cada término va **entrecomillado** porque la sintaxis de FTS5 tiene operadores propios
 * (`-`, `*`, `:`, `^`, `(`, `NEAR`, `OR`…): buscar `wi-fi` o `foo(bar` sin escapar no da cero
 * resultados, lanza un error de sintaxis. Entrecomillado se trata como texto literal.
 *
 * Al último término se le añade `*` para que la búsqueda vaya encontrando cosas mientras se
 * escribe ("conf" ya encuentra "configuración"). Solo al último: aplicarlo a todos volvería
 * demasiado laxa una búsqueda de varias palabras.
 *
 * Devuelve null si no queda ningún término utilizable, para no lanzar una consulta vacía.
 */
fun toFtsMatchQuery(raw: String): String? {
    val terms = raw.split(' ', '\t', '\n')
        .map { it.replace("\"", "").trim() }
        // Un término sin letras ni dígitos no aporta y `""` es sintaxis inválida en FTS5.
        .filter { term -> term.any { it.isLetterOrDigit() } }
    if (terms.isEmpty()) return null
    return terms.mapIndexed { index, term ->
        val quoted = "\"$term\""
        // Prefijo solo si el término da para discriminar algo.
        if (index == terms.lastIndex && term.length >= 2) "$quoted*" else quoted
    }.joinToString(" ")
}

/**
 * Parte el fragmento devuelto por `snippet()` en trozos normales y coincidentes.
 *
 * Se hace aquí y no en la UI porque es lógica pura, y se hace a partir de los delimitadores
 * de FTS5 en vez de volver a buscar la query en el texto: el tokenizador ignora acentos, así
 * que "sesion" coincide con "sesión" y un `contains` literal no encontraría nada que resaltar.
 */
fun parseSnippet(raw: String): List<SnippetSegment> {
    if (MATCH_START !in raw) return listOf(SnippetSegment(raw, isMatch = false))
    val out = mutableListOf<SnippetSegment>()
    val current = StringBuilder()
    var inMatch = false
    raw.forEach { ch ->
        when (ch) {
            MATCH_START, MATCH_END -> {
                if (current.isNotEmpty()) {
                    out += SnippetSegment(current.toString(), inMatch)
                    current.clear()
                }
                inMatch = ch == MATCH_START
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) out += SnippetSegment(current.toString(), inMatch)
    return out
}
