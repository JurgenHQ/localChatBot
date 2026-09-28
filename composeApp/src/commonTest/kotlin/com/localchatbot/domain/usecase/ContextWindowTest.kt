package com.localchatbot.domain.usecase

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextWindowTest {

    private fun msg(id: String, role: Role, content: String = "x".repeat(10)) =
        ChatMessage(id = id, role = role, content = content, timestampEpochMs = 0L)

    /** Coste = longitud del contenido: hace las cuentas de los tests triviales. */
    private val cost: (ChatMessage) -> Int = { it.content.length }

    /** u0 a0 u1 a1 … con 10 "tokens" por mensaje. */
    private fun conversation(turns: Int): List<ChatMessage> = (0 until turns).flatMap { i ->
        listOf(msg("u$i", Role.User), msg("a$i", Role.Assistant))
    }

    // ── window ────────────────────────────────────────────────────────────────

    @Test
    fun historialQueCabeEntraEntero() {
        val history = conversation(3)
        val w = ContextWindow.window(history, budget = 1_000, previousStartId = null, estimate = cost)
        assertEquals(history, w.windowed)
        assertTrue(w.discarded.isEmpty())
        assertNull(w.startId)
    }

    @Test
    fun alPasarseRecortaHastaLaMitadDelPresupuesto() {
        val history = conversation(10) // 20 mensajes × 10 = 200
        val w = ContextWindow.window(history, budget = 100, previousStartId = null, estimate = cost)
        val used = w.windowed.sumOf(cost)
        assertTrue(used <= 50, "la ventana debería quedar en ~50% del presupuesto, usó $used")
        assertEquals(history.last(), w.windowed.last())
        assertEquals(history.size, w.windowed.size + w.discarded.size)
        assertEquals(w.windowed.first().id, w.startId)
    }

    @Test
    fun histeresisMantieneElCorteMientrasQuepa() {
        val history = conversation(10)
        val first = ContextWindow.window(history, budget = 100, previousStartId = null, estimate = cost)
        // Dos turnos más: sigue cabiendo desde el mismo inicio → mismo corte.
        val grown = history + msg("u10", Role.User) + msg("a10", Role.Assistant)
        val second = ContextWindow.window(grown, budget = 100, previousStartId = first.startId, estimate = cost)
        assertEquals(first.startId, second.startId)
        assertEquals(first.discarded, second.discarded)
    }

    @Test
    fun sinHisteresisElCorteSeMoveriaEnCadaTurno() {
        // Comprueba que la histéresis es lo que fija el corte: con el corte anterior
        // ignorado, el nuevo corte se recalcula (puede coincidir o no), pero con él se
        // conserva aunque ya no fuera el que se elegiría desde cero.
        val history = conversation(10)
        val first = ContextWindow.window(history, budget = 100, previousStartId = null, estimate = cost)
        val grown = history + (10 until 14).flatMap { i -> listOf(msg("u$i", Role.User), msg("a$i", Role.Assistant)) }
        val kept = ContextWindow.window(grown, budget = 200, previousStartId = first.startId, estimate = cost)
        val fresh = ContextWindow.window(grown, budget = 200, previousStartId = null, estimate = cost)
        assertEquals(first.startId, kept.startId)
        assertNotEquals(kept.startId, fresh.startId)
    }

    @Test
    fun siElCorteAnteriorYaNoCabeSeHaceUnoNuevo() {
        val history = conversation(10)
        val first = ContextWindow.window(history, budget = 100, previousStartId = null, estimate = cost)
        val grown = history + (10 until 20).flatMap { i -> listOf(msg("u$i", Role.User), msg("a$i", Role.Assistant)) }
        val second = ContextWindow.window(grown, budget = 100, previousStartId = first.startId, estimate = cost)
        assertNotEquals(first.startId, second.startId)
        assertTrue(second.windowed.sumOf(cost) <= 50)
    }

    @Test
    fun corteDesconocidoSeIgnora() {
        val history = conversation(10)
        val w = ContextWindow.window(history, budget = 100, previousStartId = "no-existe", estimate = cost)
        assertTrue(w.windowed.sumOf(cost) <= 50)
    }

    @Test
    fun nuncaEmpiezaConResultadosDeToolHuerfanos() {
        val history = listOf(
            msg("u0", Role.User, "x".repeat(100)),
            msg("a0", Role.Assistant, "x"),
            msg("t0", Role.Tool, "x".repeat(20)),
            msg("t1", Role.Tool, "x".repeat(20)),
            msg("a1", Role.Assistant, "x".repeat(5))
        )
        // Objetivo 25: entrarían a1 + t1 → el corte cae en un tool y debe retroceder
        // hasta el assistant anunciador.
        val w = ContextWindow.window(history, budget = 50, previousStartId = null, estimate = cost)
        assertEquals("a0", w.windowed.first().id)
        assertEquals(listOf("u0"), w.discarded.map { it.id })
    }

    @Test
    fun unUnicoResultadoDeToolEnormeNoVaciaLaVentana() {
        val history = listOf(
            msg("u0", Role.User),
            msg("a0", Role.Assistant),
            msg("t0", Role.Tool, "x".repeat(10_000))
        )
        val w = ContextWindow.window(history, budget = 100, previousStartId = null, estimate = cost)
        assertTrue(w.windowed.isNotEmpty())
        assertEquals("a0", w.windowed.first().id)
        assertEquals("t0", w.windowed.last().id)
    }

    @Test
    fun elUltimoMensajeEntraSiempre() {
        val history = listOf(msg("u0", Role.User), msg("u1", Role.User, "x".repeat(10_000)))
        val w = ContextWindow.window(history, budget = 100, previousStartId = null, estimate = cost)
        assertEquals(listOf("u1"), w.windowed.map { it.id })
        assertEquals(listOf("u0"), w.discarded.map { it.id })
    }

    // ── applyCompaction ───────────────────────────────────────────────────────

    @Test
    fun compactacionDescartaHastaElCorteYLosToolHuerfanos() {
        val history = listOf(
            msg("u0", Role.User), msg("a0", Role.Assistant),
            msg("t0", Role.Tool), msg("a1", Role.Assistant), msg("u1", Role.User)
        )
        val c = ContextWindow.applyCompaction(history, "a0")
        assertTrue(c.compacted)
        assertEquals(listOf("a1", "u1"), c.history.map { it.id })
    }

    @Test
    fun compactacionConIdInexistenteNoRecorta() {
        val history = conversation(2)
        val c = ContextWindow.applyCompaction(history, "borrado")
        assertFalse(c.compacted)
        assertEquals(history, c.history)
    }

    // ── truncationNotice ──────────────────────────────────────────────────────

    @Test
    fun avisoUsaElResumenSiExiste() {
        val notice = ContextWindow.truncationNotice(conversation(2), emptyList(), truncated = true, contextSummary = "RESUMEN")
        assertEquals("Resumen del historial anterior:\nRESUMEN", notice)
    }

    @Test
    fun avisoSinResumenAnclaLaPeticionOriginal() {
        val history = listOf(msg("u0", Role.User, "haz X"), msg("a0", Role.Assistant), msg("u1", Role.User))
        val notice = ContextWindow.truncationNotice(history, history.drop(2), truncated = true, contextSummary = null)!!
        assertTrue(notice.contains("haz X"))
    }

    @Test
    fun sinRecorteNoHayAviso() {
        assertNull(ContextWindow.truncationNotice(conversation(2), conversation(2), truncated = false, contextSummary = "R"))
    }

    // ── assemble ──────────────────────────────────────────────────────────────

    @Test
    fun systemUnicoEnPosicionCero() {
        val out = ContextWindow.assemble("SYS", conversation(2), "sys-id", turnContext = "WS", nudge = "N")
        assertEquals(Role.System, out.first().role)
        assertEquals("SYS", out.first().content)
        assertEquals(1, out.count { it.role == Role.System })
    }

    @Test
    fun contextoDelTurnoVaSoloEnElUltimoUser() {
        val history = conversation(2) // u0 a0 u1 a1
        val out = ContextWindow.assemble("SYS", history, "sys-id", turnContext = "<workspace>W</workspace>")
        val u0 = out.first { it.id == "u0" }
        val u1 = out.first { it.id == "u1" }
        assertFalse(u0.content.contains("<workspace>"))
        assertTrue(u1.content.startsWith("<workspace>W</workspace>"))
        assertTrue(u1.content.endsWith(history.first { it.id == "u1" }.content))
        assertFalse(out.first().content.contains("<workspace>"), "el workspace no debe ir en el system")
    }

    @Test
    fun cambiarElWorkspaceNoCambiaElSystemNiLoAnteriorAlUltimoUser() {
        val history = conversation(3)
        val a = ContextWindow.assemble("SYS", history, "sys-id", turnContext = "git: limpio")
        val b = ContextWindow.assemble("SYS", history, "sys-id", turnContext = "git: M Foo.kt")
        val lastUser = history.indexOfLast { it.role == Role.User } + 1 // +1 por el system
        assertEquals(a.take(lastUser), b.take(lastUser))
    }

    @Test
    fun nudgeVaAlFinalComoUserTrasElAssistant() {
        val history = conversation(2) // termina en assistant
        val out = ContextWindow.assemble("SYS", history, "sys-id", nudge = "llama a la tool")
        val last = out.last()
        assertEquals(Role.User, last.role)
        assertEquals(ContextWindow.NUDGE_MESSAGE_ID, last.id)
        assertTrue(last.content.contains("llama a la tool"))
        // El resto no cambia: el nudge no invalida nada del prefijo.
        assertEquals(ContextWindow.assemble("SYS", history, "sys-id"), out.dropLast(1))
    }

    @Test
    fun nudgeSePegaAlUltimoUserSiLaPeticionTerminaEnUser() {
        val history = conversation(1) + msg("u1", Role.User, "pregunta")
        val out = ContextWindow.assemble("SYS", history, "sys-id", nudge = "N")
        assertEquals(history.size + 1, out.size)
        assertTrue(out.last().content.startsWith("pregunta"))
        assertTrue(out.last().content.contains("N"))
    }

    @Test
    fun sinUserEnLaVentanaElContextoVaAlSystem() {
        val history = listOf(msg("a0", Role.Assistant), msg("t0", Role.Tool))
        val out = ContextWindow.assemble("SYS", history, "sys-id", turnContext = "WS")
        assertEquals("SYS\n\nWS", out.first().content)
    }

    @Test
    fun sinSystemNoSeAnadeMensajeSystem() {
        val history = conversation(1)
        val out = ContextWindow.assemble("", history, "sys-id")
        assertEquals(history, out)
    }
}
