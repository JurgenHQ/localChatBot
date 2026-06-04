package com.localchatbot.core.fs

/**
 * Stub: en Android las tools de filesystem/shell reportan `isAvailable=false`,
 * por lo que estos métodos no deberían invocarse jamás. Existen para satisfacer
 * el contrato `expect`.
 */
actual class FilesystemAgent {
    actual fun resolveSafePath(workspace: String?, input: String, allowOutside: Boolean): SafePathResult =
        SafePathResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun createFile(absPath: String, content: String, overwrite: Boolean): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun createDirectory(absPath: String): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun readFile(absPath: String, maxBytes: Int): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun listDirectory(absPath: String): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun runCommand(command: String, workingDir: String, timeoutSeconds: Int, background: Boolean, startupCheckSeconds: Int): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")
}
