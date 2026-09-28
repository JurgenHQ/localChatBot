package com.localchatbot.presentation.components.molecules

/**
 * Parte un texto markdown en bloques de nivel superior, cortando en las líneas en blanco
 * que quedan **fuera** de un bloque de código (``` o ~~~). Las líneas en blanco del corte
 * no se incluyen en ningún bloque.
 *
 * Existe para el render en streaming: con un único `Markdown`, cada refresco (cada 120 ms)
 * re-parseaba y re-maquetaba la respuesta entera, así que el coste crecía con lo largo de la
 * respuesta. Renderizando un `Markdown` por bloque, los bloques ya cerrados no cambian de
 * texto y Compose se salta su recomposición; solo se re-parsea el último, el que sigue
 * creciendo.
 *
 * Criterios, conservadores a propósito (un corte de más cambia cómo se ve el markdown):
 * - Un fence sin cerrar se queda entero en el último bloque, líneas en blanco incluidas.
 * - No se corta si la línea siguiente al hueco empieza con espacio o tab: es la
 *   continuación de un ítem de lista o código indentado, y separarlos rompería el ítem.
 */
fun splitMarkdownBlocks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    val blocks = mutableListOf<String>()
    val current = mutableListOf<String>()
    var fence: String? = null
    var pendingBlank = 0

    fun closeBlock() {
        if (current.isNotEmpty()) blocks += current.joinToString("\n")
        current.clear()
    }

    for (line in lines) {
        if (fence != null) {
            current += line
            if (isFenceLine(line, fence)) fence = null
            continue
        }
        if (line.isBlank()) {
            if (current.isNotEmpty()) pendingBlank++
            continue
        }
        if (pendingBlank > 0) {
            if (line.first() == ' ' || line.first() == '\t') {
                repeat(pendingBlank) { current += "" }
            } else {
                closeBlock()
            }
            pendingBlank = 0
        }
        current += line
        fence = openingFence(line)
    }
    closeBlock()
    return blocks
}

private val FENCE_REGEX = Regex("""^ {0,3}(`{3,}|~{3,})""")

/** El marcador del fence que abre [line] (``` / ~~~, con su longitud), o null. */
private fun openingFence(line: String): String? = FENCE_REGEX.find(line)?.groupValues?.get(1)

/** Cierra el fence [open] si es del mismo carácter, al menos igual de largo y sin info string. */
private fun isFenceLine(line: String, open: String): Boolean {
    val marker = FENCE_REGEX.find(line)?.groupValues?.get(1) ?: return false
    return marker[0] == open[0] && marker.length >= open.length &&
        line.trim().length == marker.length
}
