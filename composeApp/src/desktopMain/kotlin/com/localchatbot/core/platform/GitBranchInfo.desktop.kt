package com.localchatbot.core.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val GIT_TIMEOUT_SECONDS = 5L

/**
 * `git rev-parse --abbrev-ref HEAD` en un proceso aparte; devuelve null si [workspaceDir]
 * no existe, no es un repo git, o el binario `git` no está disponible. Nunca lanza.
 */
actual suspend fun currentGitBranch(workspaceDir: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(workspaceDir)
        if (!dir.isDirectory) return@withContext null
        val proc = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@withContext null
        }
        if (proc.exitValue() != 0) null else out.trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
