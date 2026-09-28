package com.localchatbot.data.repository

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MessageOverlaysTest {

    private fun msg(id: String, content: String = "", reasoning: String? = null) =
        ChatMessage(id = id, role = Role.Assistant, content = content, timestampEpochMs = 0, reasoning = reasoning)

    @Test
    fun sinOverlaysDevuelveLaMismaLista() {
        val messages = listOf(msg("a", "hola"))
        assertSame(messages, applyOverlays(messages, emptyMap(), emptyMap()))
    }

    @Test
    fun soloCambiaElMensajeConPreview() {
        val a = msg("a", "persistido")
        val b = msg("b", "viejo", reasoning = "pienso")
        val out = applyOverlays(
            listOf(a, b),
            emptyMap(),
            mapOf("b" to StreamingPreview("s", content = "viejo y nuevo", reasoning = null))
        )
        assertSame(a, out[0])
        assertEquals("viejo y nuevo", out[1].content)
        // null en el preview = sin cambios: se conserva lo de la BD.
        assertEquals("pienso", out[1].reasoning)
    }

    @Test
    fun combinaMediaYPreviewEnElMismoMensaje() {
        val out = applyOverlays(
            listOf(msg("a")),
            mapOf("a" to ("data:image/png;base64,xx" to null)),
            mapOf("a" to StreamingPreview("s", content = "texto", reasoning = "razono"))
        )
        assertEquals("data:image/png;base64,xx", out[0].imageDataUrl)
        assertEquals(null, out[0].videoDataUrl)
        assertEquals("texto", out[0].content)
        assertEquals("razono", out[0].reasoning)
    }

    @Test
    fun ignoraEntradasDeMensajesQueNoEstan() {
        val a = msg("a", "x")
        val out = applyOverlays(listOf(a), emptyMap(), mapOf("borrado" to StreamingPreview("s", "y", null)))
        assertSame(a, out[0])
    }
}
