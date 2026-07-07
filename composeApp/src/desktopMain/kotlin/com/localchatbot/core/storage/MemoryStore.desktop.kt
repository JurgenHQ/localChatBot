package com.localchatbot.core.storage

import java.io.File

actual class MemoryStore {
    // Hermano de skills/ y tools.md: ~/.localchatbot/memory.md
    private val baseDir = File(System.getProperty("user.home"), ".localchatbot")
    private val file = File(baseDir, "memory.md")

    actual val isAvailable: Boolean = true

    actual fun read(): String? = runCatching {
        if (!file.exists()) return null
        file.readText()
    }.getOrNull()

    actual fun append(entry: String): Boolean = runCatching {
        val clean = entry.trim()
        if (clean.isEmpty()) return false
        baseDir.mkdirs()
        if (!file.exists()) file.writeText(MEMORY_HEADER)
        // Una entrada = un bullet. Si el modelo ya manda "- ", no duplicar.
        val bullet = if (clean.startsWith("- ")) clean else "- $clean"
        val existing = file.readText()
        val sep = if (existing.endsWith("\n")) "" else "\n"
        file.writeText(existing + sep + bullet + "\n")
        true
    }.getOrDefault(false)
}

actual fun createMemoryStore(): MemoryStore = MemoryStore()
