package com.localchatbot.core.hooks

/**
 * Almacén de los hooks del agente (`hooks.json`), hermano de `tools.md` y `memory.md`
 * (`~/.localchatbot/hooks.json` en desktop).
 *
 * Va a archivo y no a `AppPreferences` por dos motivos: es configuración que se edita a
 * mano, con la misma forma que `tools.md` (curada, no generada por la UI), y así no hace
 * falta ni una pantalla de ajustes nueva ni una clave más en settings.
 *
 * Solo desktop tiene impl real: los hooks ejecutan comandos de shell.
 */
expect class HooksStore {
    val isAvailable: Boolean

    /** Hooks configurados. Lista vacía si no hay archivo, está roto, o la plataforma no aplica. */
    fun load(): List<AgentHook>
}

expect fun createHooksStore(): HooksStore
