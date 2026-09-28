package com.localchatbot.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactionTest {

    private val server = McpServerConfig(
        id = "gh",
        name = "GitHub",
        url = "https://example.com/mcp",
        headers = mapOf("Authorization" to "Bearer abc", "Accept" to "application/json"),
        env = mapOf("GITHUB_TOKEN" to "ghp_x", "LOG_LEVEL" to "debug")
    )

    @Test
    fun reconoceNombresDeCredenciales() {
        listOf("Authorization", "X-API-Key", "OPENAI_API_KEY", "DB_PASSWORD", "Cookie", "client_secret").forEach {
            assertTrue(SecretRedaction.isSensitiveName(it), it)
        }
        listOf("Accept", "LOG_LEVEL", "NODE_ENV", "PORT").forEach {
            assertFalse(SecretRedaction.isSensitiveName(it), it)
        }
    }

    @Test
    fun vaciaSoloLasCredenciales() {
        val redacted = SecretRedaction.redactMcp(server)
        assertEquals(mapOf("Authorization" to "", "Accept" to "application/json"), redacted.headers)
        assertEquals(mapOf("GITHUB_TOKEN" to "", "LOG_LEVEL" to "debug"), redacted.env)
    }

    @Test
    fun alImportarRellenaLoVacioConLoActual() {
        val imported = SecretRedaction.redactMcp(server).copy(env = mapOf("GITHUB_TOKEN" to "", "LOG_LEVEL" to "info"))
        val merged = SecretRedaction.fillRedacted(imported, server)
        assertEquals("Bearer abc", merged.headers["Authorization"])
        assertEquals("ghp_x", merged.env["GITHUB_TOKEN"])
        // Lo que sí trae el archivo gana.
        assertEquals("info", merged.env["LOG_LEVEL"])
    }
}
