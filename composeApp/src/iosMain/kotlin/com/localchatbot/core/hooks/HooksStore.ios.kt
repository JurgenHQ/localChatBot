package com.localchatbot.core.hooks

/** Stub: los hooks ejecutan comandos de shell, que solo existen en desktop. */
actual class HooksStore {
    actual val isAvailable: Boolean = false
    actual fun load(): List<AgentHook> = emptyList()
}

actual fun createHooksStore(): HooksStore = HooksStore()
