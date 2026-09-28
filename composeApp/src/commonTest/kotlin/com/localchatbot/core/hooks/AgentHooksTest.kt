package com.localchatbot.core.hooks

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentHooksTest {

    private val afterTurn = AgentHook(name = "tests", command = "gradle test", event = AgentHook.EVENT_AFTER_TURN)

    @Test
    fun unHookSinEventoEsAfterTool() {
        // hooks.json escritos antes de que existiera `event` siguen funcionando igual.
        val hook = Json.decodeFromString(AgentHook.serializer(), """{"command":"fmt","tools":["edit_file"]}""")
        assertEquals(AgentHook.EVENT_AFTER_TOOL, hook.event)
        assertTrue(hook.matches("edit_file"))
        assertFalse(hook.firesAfterTurn(setOf("edit_file")))
    }

    @Test
    fun afterTurnSoloCorreSiSeMutoElWorkspace() {
        assertTrue(afterTurn.firesAfterTurn(setOf("edit_file")))
        assertFalse(afterTurn.firesAfterTurn(emptySet()))
        // Y nunca como hook de tool.
        assertFalse(afterTurn.matches("edit_file"))
    }

    @Test
    fun afterTurnConToolsFiltraPorLasUsadas() {
        val hook = afterTurn.copy(tools = listOf("run_command"))
        assertTrue(hook.firesAfterTurn(setOf("edit_file", "run_command")))
        assertFalse(hook.firesAfterTurn(setOf("edit_file")))
    }

    @Test
    fun desactivadoOSinComandoNoCorre() {
        assertFalse(afterTurn.copy(enabled = false).firesAfterTurn(setOf("edit_file")))
        assertFalse(afterTurn.copy(command = " ").firesAfterTurn(setOf("edit_file")))
    }

    @Test
    fun elJsonDeEjemploSigueSiendoValido() {
        val config = Json.decodeFromString(AgentHooksConfig.serializer(), DEFAULT_HOOKS_JSON)
        assertEquals(1, config.hooks.count { it.event == AgentHook.EVENT_AFTER_TURN })
        assertTrue(config.hooks.none { it.enabled })
    }
}
