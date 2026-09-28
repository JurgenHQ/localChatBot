package com.localchatbot.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdaterTest {

    private fun release(vararg names: String) = Json.parseToJsonElement(
        """{"assets":[${names.joinToString(",") { """{"name":"$it","browser_download_url":"https://x/$it"}""" }}]}"""
    ) as JsonObject

    @Test
    fun comparaNumericamenteYNoComoTexto() {
        assertTrue(compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(compareVersions("1.1", "1.0.99") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("1.0.6", "1.0.26") < 0)
    }

    @Test
    fun eligeElMsiMasNuevo() {
        val update = pickUpdate(release("LocalChatBot-1.0.26.msi", "LocalChatBot-1.0.31.msi", "notas.txt"), "1.0.6")
        assertEquals("1.0.31", update?.version)
        assertEquals("https://x/LocalChatBot-1.0.31.msi", update?.downloadUrl)
    }

    @Test
    fun nadaSiYaEstaAlDia() {
        assertNull(pickUpdate(release("LocalChatBot-1.0.26.msi"), "1.0.26"))
        assertNull(pickUpdate(release("LocalChatBot-1.0.26.msi"), "1.0.30"))
        assertNull(pickUpdate(release(), "1.0.6"))
    }
}
