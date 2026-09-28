package com.localchatbot.domain.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageSearchTest {

    @Test
    fun entrecomillaCadaTerminoYPrefijaElUltimo() {
        assertEquals("\"hola\" \"mundo\"*", toFtsMatchQuery("hola mundo"))
    }

    @Test
    fun losOperadoresDeFts5QuedanComoTexto() {
        assertEquals("\"wi-fi\"*", toFtsMatchQuery("wi-fi"))
        assertEquals("\"foo(bar\"*", toFtsMatchQuery("foo(bar"))
    }

    @Test
    fun quitaLasComillasDelUsuario() {
        assertEquals("\"say\" \"hi\"*", toFtsMatchQuery("say \"hi\""))
    }

    @Test
    fun unaLetraSueltaNoLlevaPrefijo() {
        assertEquals("\"a\"", toFtsMatchQuery("a"))
    }

    @Test
    fun sinTerminosUtilesDevuelveNull() {
        assertNull(toFtsMatchQuery("   "))
        assertNull(toFtsMatchQuery("- * \"\""))
    }

    @Test
    fun parteElSnippetEnTrozos() {
        assertEquals(
            listOf(
                SnippetSegment("la ", false),
                SnippetSegment("sesión", true),
                SnippetSegment(" activa", false)
            ),
            parseSnippet("la \u0002sesión\u0003 activa")
        )
    }

    @Test
    fun unSnippetSinCoincidenciasEsUnSoloTrozo() {
        assertEquals(listOf(SnippetSegment("texto", false)), parseSnippet("texto"))
    }
}
