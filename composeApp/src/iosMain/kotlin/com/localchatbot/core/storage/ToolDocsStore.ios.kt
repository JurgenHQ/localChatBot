package com.localchatbot.core.storage

/** Stub: tools.md solo en desktop. */
actual class ToolDocsStore {
    actual val isAvailable: Boolean = false
    actual fun read(): String? = null
}

actual fun createToolDocsStore(): ToolDocsStore = ToolDocsStore()
