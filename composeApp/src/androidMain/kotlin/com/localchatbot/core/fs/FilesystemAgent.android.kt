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

    actual suspend fun writeBytes(absPath: String, bytes: ByteArray, overwrite: Boolean): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun createDirectory(absPath: String): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun readFile(absPath: String, offset: Int, limit: Int, maxBytes: Int): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun readFileRaw(absPath: String, maxBytes: Int): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun listDirectory(absPath: String): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun searchFiles(absPath: String, pattern: String, literal: Boolean, caseSensitive: Boolean, fileGlob: String?, maxResults: Int, workspaceRoot: String?): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun editFile(absPath: String, oldString: String?, newString: String, replaceAll: Boolean, startLine: Int?, endLine: Int?): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun multiEditFile(absPath: String, edits: List<MultiFileEdit>): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun deletePath(absPath: String, recursive: Boolean): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")

    actual suspend fun runCommand(command: String, workingDir: String, timeoutSeconds: Int, background: Boolean, startupCheckSeconds: Int): FsResult =
        FsResult.Err("Filesystem tools no disponibles en Android")
}
