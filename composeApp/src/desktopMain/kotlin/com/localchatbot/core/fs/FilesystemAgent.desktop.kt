package com.localchatbot.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolute
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Implementación real para JVM. Toda la I/O ocurre en [Dispatchers.IO].
 */
actual class FilesystemAgent {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    actual fun resolveSafePath(
        workspace: String?,
        input: String,
        allowOutside: Boolean
    ): SafePathResult {
        if (input.isBlank()) return SafePathResult.Err("Path vacío")

        val parsed: Path = try {
            Paths.get(input)
        } catch (e: InvalidPathException) {
            return SafePathResult.Err("Path inválido: ${e.message}")
        }

        val resolved: Path = try {
            when {
                parsed.isAbsolute -> parsed.normalize().absolute()
                workspace != null -> Paths.get(workspace).resolve(parsed).normalize().absolute()
                allowOutside -> parsed.normalize().absolute()
                else -> return SafePathResult.Err(
                    "Sin workspace configurado y sin permiso para acceder fuera del workspace"
                )
            }
        } catch (e: InvalidPathException) {
            return SafePathResult.Err("Path inválido tras normalizar: ${e.message}")
        }

        val inside = workspace != null &&
            resolved.startsWith(Paths.get(workspace).normalize().absolute())

        if (!inside && !allowOutside) {
            return SafePathResult.Err(
                "La ruta queda fuera del workspace y el acceso externo está deshabilitado"
            )
        }

        return SafePathResult.Ok(resolved.toString(), inside)
    }

    actual suspend fun createFile(
        absPath: String,
        content: String,
        overwrite: Boolean
    ): FsResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = Paths.get(absPath)
            if (Files.exists(path) && !overwrite) {
                return@runCatching FsResult.Err(
                    "El archivo ya existe en $absPath (overwrite=false)"
                )
            }
            val existed = Files.exists(path)
            path.parent?.let { Files.createDirectories(it) }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            Files.write(
                path,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            FsResult.Ok(buildJsonObject {
                put("success", true)
                put("path", absPath)
                put("bytesWritten", bytes.size)
                put("overwritten", overwrite && existed)
            })
        }.getOrElse { e -> FsResult.Err(e.message ?: e::class.simpleName ?: "Error desconocido") }
    }

    actual suspend fun createDirectory(absPath: String): FsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = Paths.get(absPath)
                Files.createDirectories(path)
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                })
            }.getOrElse { e -> FsResult.Err(e.message ?: "Error creando directorio") }
        }

    actual suspend fun readFile(absPath: String, maxBytes: Int): FsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = Paths.get(absPath)
                if (!path.isRegularFile()) {
                    return@runCatching FsResult.Err("No es un archivo regular: $absPath")
                }
                val total = path.fileSize()
                val truncated = total > maxBytes
                val bytes = if (truncated) {
                    Files.newInputStream(path).use { input ->
                        ByteArray(maxBytes).also { buf ->
                            var read = 0
                            while (read < buf.size) {
                                val n = input.read(buf, read, buf.size - read)
                                if (n <= 0) break
                                read += n
                            }
                        }
                    }
                } else {
                    Files.readAllBytes(path)
                }
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("size", total)
                    put("truncated", truncated)
                    put("content", String(bytes, StandardCharsets.UTF_8))
                })
            }.getOrElse { e -> FsResult.Err(e.message ?: "Error leyendo archivo") }
        }

    actual suspend fun listDirectory(absPath: String): FsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = Paths.get(absPath)
                if (!path.isDirectory()) {
                    return@runCatching FsResult.Err("No es un directorio: $absPath")
                }
                val entries = Files.list(path).use { stream ->
                    stream.toArray { arrayOfNulls<Path>(it) }
                        .filterNotNull()
                        .sortedBy { it.name.lowercase() }
                }
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("count", entries.size)
                    put("entries", buildJsonArray {
                        entries.forEach { entry ->
                            add(buildJsonObject {
                                put("name", entry.name)
                                val isDir = entry.isDirectory()
                                put("type", if (isDir) "dir" else "file")
                                if (!isDir) {
                                    runCatching { put("size", entry.fileSize()) }
                                }
                            })
                        }
                    })
                })
            }.getOrElse { e -> FsResult.Err(e.message ?: "Error listando directorio") }
        }

    actual suspend fun editFile(
        absPath: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): FsResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = Paths.get(absPath)
            if (!path.isRegularFile()) {
                return@runCatching FsResult.Err("No es un archivo regular: $absPath")
            }
            if (oldString.isEmpty()) {
                return@runCatching FsResult.Err("'old_string' no puede estar vacío")
            }
            if (oldString == newString) {
                return@runCatching FsResult.Err("'old_string' y 'new_string' son idénticos")
            }
            val original = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            var occurrences = 0
            var idx = original.indexOf(oldString)
            while (idx >= 0) {
                occurrences++
                idx = original.indexOf(oldString, idx + oldString.length)
            }
            when {
                occurrences == 0 -> return@runCatching FsResult.Err(
                    "'old_string' no aparece en el archivo. Lee el archivo de nuevo y copia el texto exacto."
                )
                occurrences > 1 && !replaceAll -> return@runCatching FsResult.Err(
                    "'old_string' aparece $occurrences veces. Amplía el texto para hacerlo único, " +
                        "o pasa replace_all=true para reemplazar todas las ocurrencias."
                )
            }
            val updated = original.replace(oldString, newString)
            val bytes = updated.toByteArray(StandardCharsets.UTF_8)
            Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            FsResult.Ok(buildJsonObject {
                put("success", true)
                put("path", absPath)
                put("replacements", occurrences)
                put("bytesWritten", bytes.size)
            })
        }.getOrElse { e -> FsResult.Err(e.message ?: "Error editando archivo") }
    }

    actual suspend fun deletePath(absPath: String, recursive: Boolean): FsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = Paths.get(absPath)
                if (!Files.exists(path)) {
                    return@runCatching FsResult.Err("No existe: $absPath")
                }
                var deletedCount = 0
                if (path.isDirectory()) {
                    val isEmpty = Files.list(path).use { it.findFirst().isEmpty }
                    if (!isEmpty && !recursive) {
                        return@runCatching FsResult.Err(
                            "El directorio no está vacío. Pasa recursive=true para borrarlo con su contenido."
                        )
                    }
                    // walk emite padres antes que hijos; borramos en orden inverso.
                    Files.walk(path).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach {
                            Files.delete(it)
                            deletedCount++
                        }
                    }
                } else {
                    Files.delete(path)
                    deletedCount = 1
                }
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("deletedEntries", deletedCount)
                })
            }.getOrElse { e -> FsResult.Err(e.message ?: "Error eliminando") }
        }

    actual suspend fun runCommand(
        command: String,
        workingDir: String,
        timeoutSeconds: Int,
        background: Boolean,
        startupCheckSeconds: Int
    ): FsResult = withContext(Dispatchers.IO) {
        runCatching {
            val cwd = File(workingDir)
            if (!cwd.isDirectory) {
                return@runCatching FsResult.Err("workingDir no es un directorio: $workingDir")
            }

            val args = if (isWindows) {
                arrayOf("cmd", "/c", command)
            } else {
                // -l: login shell → carga .zprofile (homebrew PATH)
                // -i: interactive → carga .zshrc (nvm, volta, fnm, etc.)
                val userShell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
                arrayOf(userShell, "-i", "-l", "-c", command)
            }

            val proc = ProcessBuilder(*args)
                .directory(cwd)
                .redirectErrorStream(false)
                .start()

            if (background) {
                // Modo background: lanza readers concurrentes en daemon threads para
                // capturar output inicial, espera [startupCheckSeconds] y retorna con
                // el PID dejando el proceso corriendo.
                val checkMs = startupCheckSeconds.coerceIn(1, 30) * 1_000L
                val stdoutBuf = java.io.ByteArrayOutputStream()
                val stderrBuf = java.io.ByteArrayOutputStream()

                // Readers en daemon threads — leen hasta MAX_OUTPUT_CHARS y luego
                // descartan (evita deadlock por pipe lleno) hasta que el proceso muera.
                fun startReader(input: java.io.InputStream, buf: java.io.ByteArrayOutputStream): Thread =
                    Thread {
                        try {
                            val tmp = ByteArray(4_096)
                            while (true) {
                                val n = input.read(tmp)
                                if (n <= 0) break
                                synchronized(buf) {
                                    if (buf.size() < MAX_OUTPUT_CHARS) buf.write(tmp, 0, n)
                                    // Si ya está lleno seguimos leyendo y descartando para
                                    // no bloquear el pipe del proceso.
                                }
                            }
                        } catch (_: Exception) {}
                    }.also { it.isDaemon = true; it.start() }

                val stdoutReader = startReader(proc.inputStream, stdoutBuf)
                val stderrReader = startReader(proc.errorStream, stderrBuf)

                val exited = proc.waitFor(checkMs, TimeUnit.MILLISECONDS)

                return@runCatching if (exited) {
                    stdoutReader.join(500)
                    stderrReader.join(500)
                    FsResult.Ok(buildJsonObject {
                        put("success", proc.exitValue() == 0)
                        put("exitCode", proc.exitValue())
                        put("stdout", sanitize(stdoutBuf.toString(StandardCharsets.UTF_8.name())))
                        put("stderr", sanitize(stderrBuf.toString(StandardCharsets.UTF_8.name())))
                        put("background", false)
                        put("command", command)
                        put("workingDir", workingDir)
                    })
                } else {
                    // Dar 200ms extra para que los readers vacíen lo que hay en el pipe.
                    Thread.sleep(200)
                    FsResult.Ok(buildJsonObject {
                        put("success", true)
                        put("background", true)
                        put("pid", proc.pid())
                        put("initial_stdout", sanitize(stdoutBuf.toString(StandardCharsets.UTF_8.name())))
                        put("initial_stderr", sanitize(stderrBuf.toString(StandardCharsets.UTF_8.name())))
                        put("command", command)
                        put("workingDir", workingDir)
                        put("note", "Process running in background. Use 'kill ${proc.pid()}' to stop it.")
                    })
                }
            }

            // Modo normal: bloquea hasta que termina o expira.
            val timeout = timeoutSeconds.coerceIn(1, 600).toLong()
            val finished = proc.waitFor(timeout, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runCatching FsResult.Err(
                    "Timeout: el comando excedió ${timeout}s y fue terminado"
                )
            }

            val stdout = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            val stderr = proc.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            val exitCode = proc.exitValue()

            FsResult.Ok(buildJsonObject {
                put("success", exitCode == 0)
                put("exitCode", exitCode)
                put("stdout", sanitize(stdout.take(MAX_OUTPUT_CHARS)))
                put("stderr", sanitize(stderr.take(MAX_OUTPUT_CHARS)))
                put("truncated", stdout.length > MAX_OUTPUT_CHARS || stderr.length > MAX_OUTPUT_CHARS)
                put("background", false)
                put("command", command)
                put("workingDir", workingDir)
            })
        }.getOrElse { e -> FsResult.Err(e.message ?: "Error ejecutando comando") }
    }

    /**
     * Elimina códigos ANSI (colores, cursores, etc.) y caracteres de control
     * ilegales en XML 1.0 del output de consola.
     *
     * Motivo: `PropertiesSettings` persiste con `storeToXML()`. Los chars de
     * control producen XML malformado → la escritura falla silenciosamente →
     * la sesión no se guarda.
     */
    private fun sanitize(text: String): String =
        ANSI_PATTERN.replace(text, "")
            .replace(CONTROL_CHARS_PATTERN, "")

    private companion object {
        const val MAX_OUTPUT_CHARS = 50_000

        /** Secuencias de escape ANSI: ESC [ … m , ESC ] … , ESC c, etc. */
        val ANSI_PATTERN = Regex("""\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\x07]*\x07)""")

        /** Caracteres de control ilegales en XML 1.0 (excepto \t \n \r). */
        val CONTROL_CHARS_PATTERN = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F￾￿]""")
    }
}
