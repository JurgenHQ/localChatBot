package com.localchatbot.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Implementación real (JVM). Layout en disco:
 *
 * ```
 * ~/.localchatbot/checkpoints/<sessionId>/<turnId>/
 *   manifest.json          — entradas {absPath, existedBefore, isDirectory, blobFile?, partial}
 *   blobs/<n>              — contenido previo byte a byte (archivos)
 *   blobs/<n>/…            — árbol previo (directorios borrados recursivamente)
 * ```
 */
actual class CheckpointStore {

    private val baseDir = File(System.getProperty("user.home"), ".localchatbot/checkpoints")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // Serializa snapshots concurrentes (tools en paralelo) y revert vs snapshot.
    private val mutex = Mutex()

    @Serializable
    private data class ManifestEntry(
        val absPath: String,
        val existedBefore: Boolean,
        val isDirectory: Boolean = false,
        val blobFile: String? = null,
        // true si el snapshot de un directorio se cortó por los caps: el revert
        // de esa entrada será parcial y se reporta como error informativo.
        val partial: Boolean = false
    )

    /**
     * Estado git del workspace antes del primer comando de shell del turno. [ref] es el
     * commit al que hay que volver: el de `git stash create` si había cambios locales, o el
     * HEAD si el árbol estaba limpio.
     */
    @Serializable
    private data class GitSnapshot(val workspaceDir: String, val ref: String)

    /** [gitSnapshot] tiene default: los manifests escritos antes de existir siguen leyéndose. */
    @Serializable
    private data class Manifest(
        val entries: List<ManifestEntry> = emptyList(),
        val gitSnapshot: GitSnapshot? = null
    )

    /** Corre git en [dir] y devuelve stdout recortado, o null si falla o no hay git. */
    private fun git(dir: File, vararg args: String): String? = runCatching {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(GIT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) null else out.trim()
    }.getOrNull()

    private fun turnDir(sessionId: String, turnId: String): File =
        File(File(baseDir, sanitize(sessionId)), sanitize(turnId))

    private fun sanitize(id: String): String = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun readManifest(dir: File): Manifest =
        runCatching {
            val f = File(dir, MANIFEST)
            if (f.exists()) json.decodeFromString(Manifest.serializer(), f.readText()) else Manifest()
        }.getOrDefault(Manifest())

    private fun writeManifest(dir: File, manifest: Manifest) {
        dir.mkdirs()
        File(dir, MANIFEST).writeText(json.encodeToString(Manifest.serializer(), manifest))
    }

    actual suspend fun snapshotBeforeMutation(
        sessionId: String,
        turnId: String,
        absPath: String,
        toolName: String
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val dir = turnDir(sessionId, turnId)
                val manifest = readManifest(dir)
                // Idempotente: el estado pre-turno es el que vale.
                if (manifest.entries.any { it.absPath == absPath }) return@runCatching

                val source: Path = Paths.get(absPath)
                val entry = when {
                    !Files.exists(source) ->
                        ManifestEntry(absPath = absPath, existedBefore = false)

                    Files.isDirectory(source) -> {
                        // Solo delete_file recursivo llega aquí con un directorio.
                        val blobName = "blob_${manifest.entries.size}"
                        val target = File(File(dir, BLOBS), blobName)
                        val partial = !copyTreeCapped(source, target.toPath())
                        ManifestEntry(
                            absPath = absPath,
                            existedBefore = true,
                            isDirectory = true,
                            blobFile = blobName,
                            partial = partial
                        )
                    }

                    else -> {
                        val blobName = "blob_${manifest.entries.size}"
                        val target = File(File(dir, BLOBS), blobName)
                        target.parentFile.mkdirs()
                        Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        ManifestEntry(
                            absPath = absPath,
                            existedBefore = true,
                            blobFile = blobName
                        )
                    }
                }
                writeManifest(dir, Manifest(manifest.entries + entry))
            }
            // Un fallo de snapshot nunca debe romper la tool: el caller ya envuelve
            // en runCatching, aquí solo garantizamos no dejar estado a medias.
            Unit
        }
    }

    /** Copia un árbol con caps de archivos y bytes. Devuelve false si se cortó. */
    private fun copyTreeCapped(source: Path, target: Path): Boolean {
        var files = 0
        var bytes = 0L
        var complete = true
        Files.walk(source).use { stream ->
            for (p in stream) {
                val rel = source.relativize(p)
                val dest = if (rel.toString().isEmpty()) target else target.resolve(rel)
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest)
                    continue
                }
                val size = runCatching { Files.size(p) }.getOrDefault(0L)
                if (files + 1 > MAX_TREE_FILES || bytes + size > MAX_TREE_BYTES) {
                    complete = false
                    break
                }
                Files.createDirectories(dest.parent)
                Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
                files++
                bytes += size
            }
        }
        return complete
    }

    actual suspend fun snapshotWorkspaceGit(
        sessionId: String,
        turnId: String,
        workspaceDir: String
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val dir = turnDir(sessionId, turnId)
                // Idempotente por turno: lo que vale es el estado ANTES del primer comando.
                if (readManifest(dir).gitSnapshot != null) return@withLock true

                val ws = File(workspaceDir)
                if (!ws.isDirectory) return@withLock false
                if (git(ws, "rev-parse", "--is-inside-work-tree") != "true") return@withLock false

                // `git stash create` construye el commit del estado actual SIN tocar el
                // working tree ni la pila de stashes: justo lo que hace falta para poder
                // volver luego. Devuelve vacío si no hay nada que guardar (árbol limpio),
                // y en ese caso el punto de retorno correcto es HEAD.
                val stash = git(ws, "stash", "create")?.takeIf { it.isNotBlank() }
                val ref = stash ?: git(ws, "rev-parse", "HEAD")?.takeIf { it.isNotBlank() }
                    ?: return@withLock false

                val manifest = readManifest(dir)
                writeManifest(dir, manifest.copy(gitSnapshot = GitSnapshot(ws.absolutePath, ref)))
                true
            }.getOrDefault(false)
        }
    }

    actual suspend fun hasCheckpoint(sessionId: String, turnId: String): Boolean =
        withContext(Dispatchers.IO) {
            readManifest(turnDir(sessionId, turnId)).let {
                it.entries.isNotEmpty() || it.gitSnapshot != null
            }
        }

    actual suspend fun checkpointSummary(sessionId: String, turnId: String): List<String> =
        withContext(Dispatchers.IO) {
            readManifest(turnDir(sessionId, turnId)).let { m ->
                // Para el snapshot de git se pregunta a git qué cambió desde el punto de
                // retorno: así el diálogo enseña archivos concretos y no un "algo cambió".
                val fromGit = m.gitSnapshot?.let { snap ->
                    git(File(snap.workspaceDir), "diff", "--name-only", snap.ref)
                        ?.lines()?.filter { it.isNotBlank() }
                        ?.map { "${snap.workspaceDir}/$it" }
                }.orEmpty()
                (m.entries.map { it.absPath } + fromGit).distinct()
            }
        }

    actual suspend fun revert(sessionId: String, turnId: String): CheckpointRevertResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = turnDir(sessionId, turnId)
                val manifest = readManifest(dir)
                if (manifest.entries.isEmpty() && manifest.gitSnapshot == null) {
                    return@withLock CheckpointRevertResult(
                        restored = emptyList(),
                        errors = listOf("No hay checkpoint para este turno")
                    )
                }

                val restored = mutableListOf<String>()
                val errors = mutableListOf<String>()

                // Git PRIMERO, y las entradas de archivo después: las fs tools guardan el
                // contenido byte a byte, así que su restauración es exacta y debe ser la que
                // gane si un mismo archivo aparece por las dos vías.
                manifest.gitSnapshot?.let { snap ->
                    val ws = File(snap.workspaceDir)
                    val changed = git(ws, "diff", "--name-only", snap.ref)
                        ?.lines()?.filter { it.isNotBlank() }.orEmpty()
                    if (changed.isEmpty()) {
                        // Nada que deshacer por esta vía; no es un error.
                    } else if (git(ws, "checkout", snap.ref, "--", ".") == null) {
                        errors.add("No se pudieron restaurar los archivos tocados por comandos de shell (git checkout falló)")
                    } else {
                        restored.addAll(changed.map { "${snap.workspaceDir}/$it" })
                    }
                }

                // Orden inverso: deshace las mutaciones en el orden contrario al
                // que ocurrieron dentro del turno.
                for (entry in manifest.entries.reversed()) {
                    runCatching {
                        val livePath = Paths.get(entry.absPath)
                        if (!entry.existedBefore) {
                            // Se creó en el turno → borrarlo (recursivo si es dir).
                            if (Files.exists(livePath)) {
                                if (Files.isDirectory(livePath)) {
                                    Files.walk(livePath).use { s ->
                                        s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                                    }
                                } else {
                                    Files.delete(livePath)
                                }
                            }
                        } else {
                            val blob = File(File(dir, BLOBS), entry.blobFile ?: error("manifest sin blob"))
                            if (entry.isDirectory) {
                                // Restaurar árbol: primero limpiar lo que haya, luego copiar.
                                if (Files.exists(livePath)) {
                                    Files.walk(livePath).use { s ->
                                        s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                                    }
                                }
                                copyTreeCapped(blob.toPath(), livePath)
                                if (entry.partial) {
                                    errors.add("${entry.absPath}: restaurado parcialmente (el snapshot excedió el límite)")
                                }
                            } else {
                                livePath.parent?.let { Files.createDirectories(it) }
                                Files.copy(blob.toPath(), livePath, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                        restored.add(entry.absPath)
                    }.onFailure { e ->
                        errors.add("${entry.absPath}: ${e.message ?: "error desconocido"}")
                    }
                }

                CheckpointRevertResult(restored = restored, errors = errors)
            }
        }

    actual suspend fun deleteSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { File(baseDir, sanitize(sessionId)).deleteRecursively() }
            Unit
        }
    }

    actual suspend fun pruneSession(sessionId: String, keepLastTurns: Int): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val sessionDir = File(baseDir, sanitize(sessionId))
                    val turns = sessionDir.listFiles { f -> f.isDirectory }?.toList() ?: return@runCatching
                    turns.sortedByDescending { it.lastModified() }
                        .drop(keepLastTurns)
                        .forEach { it.deleteRecursively() }
                }
                Unit
            }
        }

    private companion object {
        /** Un git colgado no puede bloquear el turno del agente. */
        const val GIT_TIMEOUT_SECONDS = 15L
        const val MANIFEST = "manifest.json"
        const val BLOBS = "blobs"

        // Caps para snapshots de directorios (delete_file recursivo).
        const val MAX_TREE_FILES = 200
        const val MAX_TREE_BYTES = 20_000_000L
    }
}
