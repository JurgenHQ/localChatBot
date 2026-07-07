package com.localchatbot.core.storage

/** Stub: memory.md solo en desktop. */
actual class MemoryStore {
    actual val isAvailable: Boolean = false
    actual fun read(): String? = null
    actual fun append(entry: String): Boolean = false
}

actual fun createMemoryStore(): MemoryStore = MemoryStore()
