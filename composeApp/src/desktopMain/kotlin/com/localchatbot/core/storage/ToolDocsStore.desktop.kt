package com.localchatbot.core.storage

import java.io.File

actual class ToolDocsStore {
    // Hermano de skills/: ~/.localchatbot/tools.md
    private val baseDir = File(System.getProperty("user.home"), ".localchatbot")
    private val file = File(baseDir, "tools.md")

    actual val isAvailable: Boolean = true

    actual fun read(): String? = runCatching {
        if (!file.exists()) {
            baseDir.mkdirs()
            file.writeText(DEFAULT_TOOLS_MD)
        }
        file.readText()
    }.getOrNull()
}

actual fun createToolDocsStore(): ToolDocsStore = ToolDocsStore()
