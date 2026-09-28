package com.localchatbot.core.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInspectorTest {

    @Test
    fun quitaElBase64DeLasDataUrls() {
        val b64 = "A".repeat(5_000)
        val body = """{"image_url":{"url":"data:image/png;base64,$b64"},"text":"hola"}"""
        val out = NetworkInspector.sanitizeBody(body)
        assertFalse(out.contains(b64))
        assertTrue(out.contains("data:image/png;base64,<"))
        assertTrue(out.contains("\"text\":\"hola\""))
    }

    @Test
    fun cuerposPequenosNoCambian() {
        val body = """{"model":"qwen","messages":[]}"""
        assertEquals(body, NetworkInspector.sanitizeBody(body))
    }

    @Test
    fun recortaCuerposGrandesConservandoCabezaYCola() {
        val body = "HEAD" + "x".repeat(500_000) + "TAIL"
        val out = NetworkInspector.sanitizeBody(body)
        assertTrue(out.length < NetworkInspector.MAX_BODY_CHARS + 200)
        assertTrue(out.startsWith("HEAD"))
        assertTrue(out.endsWith("TAIL"))
        assertTrue(out.contains("omitidos"))
    }

    @Test
    fun recordGuardaElCuerpoSaneadoYRespetaLaCapacidad() {
        val inspector = NetworkInspector(capacity = 2)
        repeat(3) { i ->
            inspector.record(
                NetworkTransaction(
                    id = "t$i", timestampEpochMs = 0, method = "POST", url = "u",
                    kind = NetworkTransaction.Kind.ChatStream,
                    requestBody = "x".repeat(200_000), responseStatus = 200,
                    responseBody = null, durationMs = 1
                )
            )
        }
        val entries = inspector.entries.value
        assertEquals(listOf("t2", "t1"), entries.map { it.id })
        assertTrue(entries.all { (it.requestBody?.length ?: 0) < NetworkInspector.MAX_BODY_CHARS + 200 })
    }
}
