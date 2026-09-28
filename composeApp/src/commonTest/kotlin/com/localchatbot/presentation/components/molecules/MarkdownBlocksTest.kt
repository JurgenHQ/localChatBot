package com.localchatbot.presentation.components.molecules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownBlocksTest {

    @Test
    fun textoVacioNoTieneBloques() {
        assertTrue(splitMarkdownBlocks("").isEmpty())
        assertTrue(splitMarkdownBlocks("\n\n").isEmpty())
    }

    @Test
    fun cortaEnLineasEnBlanco() {
        val text = "# Título\n\nPrimer párrafo\nsigue aquí.\n\n\nSegundo párrafo."
        assertEquals(
            listOf("# Título", "Primer párrafo\nsigue aquí.", "Segundo párrafo."),
            splitMarkdownBlocks(text)
        )
    }

    @Test
    fun noCortaDentroDeUnFence() {
        val code = "```kotlin\nval a = 1\n\nval b = 2\n```"
        val text = "Antes\n\n$code\n\nDespués"
        assertEquals(listOf("Antes", code, "Después"), splitMarkdownBlocks(text))
    }

    @Test
    fun unFenceSinCerrarQuedaEnteroEnElUltimoBloque() {
        val text = "Intro\n\n```\nlinea 1\n\nlinea 2"
        assertEquals(listOf("Intro", "```\nlinea 1\n\nlinea 2"), splitMarkdownBlocks(text))
    }

    @Test
    fun elCierreDebeSerDelMismoTipoYSinInfoString() {
        // "~~~" no cierra un fence abierto con ``` ni "```js" cierra nada.
        val code = "```\na\n~~~\n\n```js\nb\n```"
        assertEquals(listOf(code, "fin"), splitMarkdownBlocks("$code\n\nfin"))
    }

    @Test
    fun laContinuacionIndentadaNoSeSeparaDeSuItem() {
        val list = "- ítem uno\n\n  sigue el ítem uno\n- ítem dos"
        assertEquals(listOf(list, "Cierre"), splitMarkdownBlocks("$list\n\nCierre"))
    }

    @Test
    fun conservaTodasLasLineasConContenidoEnOrden() {
        val text = "a\n\n```\nb\n\nc\n```\n\nd\n  e\n\n\nf"
        val rejoined = splitMarkdownBlocks(text).joinToString("\n").lines().filter { it.isNotBlank() }
        assertEquals(text.lines().filter { it.isNotBlank() }, rejoined)
    }

    @Test
    fun losBloquesCerradosNoCambianAlLlegarMasTexto() {
        // La razón de ser de la función: lo ya cerrado debe mantenerse idéntico mientras
        // crece el último bloque, o Compose lo recompondría igual.
        val before = splitMarkdownBlocks("Uno\n\nDos a medias")
        val after = splitMarkdownBlocks("Uno\n\nDos a medias y completo.\n\nTres")
        assertEquals(before.first(), after.first())
    }
}
