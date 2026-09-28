package com.localchatbot.domain.export

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatExportTest {

    private fun msg(id: String, role: Role, content: String, toolCalls: List<PersistedToolCall>? = null) =
        ChatMessage(id = id, role = role, content = content, timestampEpochMs = 0, toolCalls = toolCalls)

    private val conversation = listOf(
        msg("u1", Role.User, "Lee el README"),
        msg("a1", Role.Assistant, "Voy a leerlo.", listOf(PersistedToolCall("c1", "read_file", "{}"))),
        msg("t1", Role.Tool, "{\"content\":\"# Proyecto enorme\"}"),
        msg("a2", Role.Assistant, "Es un proyecto KMP."),
        msg("u2", Role.User, "Gracias")
    )

    @Test
    fun unTurnoVaDelUsuarioHastaElSiguienteUsuario() {
        val md = ChatExport.turnToMarkdown(conversation, "u1")!!
        assertTrue(md.startsWith("## 👤 Usuario"))
        assertTrue(md.contains("🔧 `read_file`"))
        assertTrue(md.contains("Es un proyecto KMP."))
        // Ni el payload de la tool ni el turno siguiente.
        assertFalse(md.contains("Proyecto enorme"))
        assertFalse(md.contains("Gracias"))
    }

    @Test
    fun unMensajeDelAsistenteSeExportaSolo() {
        val md = ChatExport.turnToMarkdown(conversation, "a2")!!
        assertEquals("## 🤖 Asistente\n\nEs un proyecto KMP.", md)
    }

    @Test
    fun unIdInexistenteDevuelveNull() {
        assertNull(ChatExport.turnToMarkdown(conversation, "nope"))
    }

    @Test
    fun elNombreDeArchivoTransliteraYLimpia() {
        assertEquals("configuracion-del-servidor.md", ChatExport.suggestedFileName("Configuración del servidor!"))
        assertEquals("conversacion.md", ChatExport.suggestedFileName("🚀🚀"))
        assertEquals("a-b.md", ChatExport.suggestedFileName("a: / b"))
    }
}
