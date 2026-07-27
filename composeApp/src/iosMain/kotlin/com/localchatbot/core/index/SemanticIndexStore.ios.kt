package com.localchatbot.core.index

/** Stub: el índice semántico del workspace es solo desktop. */
actual class SemanticIndexStore {
    actual val isAvailable: Boolean = false
    actual fun collectFiles(workspace: String, maxFiles: Int): List<WorkspaceFile> = emptyList()
    actual fun readText(absPath: String): String? = null
    actual fun load(workspace: String): String? = null
    actual fun save(workspace: String, content: String): Boolean = false
    actual fun delete(workspace: String) {}
}

actual fun createSemanticIndexStore(): SemanticIndexStore = SemanticIndexStore()
