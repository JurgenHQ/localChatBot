package com.localchatbot.core.hooks

import kotlinx.serialization.json.Json
import java.io.File

actual class HooksStore {
    private val baseDir = File(System.getProperty("user.home"), ".localchatbot")
    private val file = File(baseDir, "hooks.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    actual val isAvailable: Boolean = true

    /**
     * Se relee en cada turno en vez de cachear: así editar el archivo tiene efecto sin
     * reiniciar la app, que es lo que se espera de algo que se toca a mano.
     *
     * Un JSON roto devuelve lista vacía en vez de propagar: un hook mal escrito no puede
     * tumbar el turno del agente.
     */
    actual fun load(): List<AgentHook> = runCatching {
        if (!file.exists()) {
            baseDir.mkdirs()
            file.writeText(DEFAULT_HOOKS_JSON)
            return emptyList()
        }
        json.decodeFromString(AgentHooksConfig.serializer(), file.readText()).hooks
    }.getOrDefault(emptyList())
}

actual fun createHooksStore(): HooksStore = HooksStore()
