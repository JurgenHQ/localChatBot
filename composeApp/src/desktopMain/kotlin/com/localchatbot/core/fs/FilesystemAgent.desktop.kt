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

    actual suspend fun writeBytes(
        absPath: String,
        bytes: ByteArray,
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

    actual suspend fun readFile(
        absPath: String,
        offset: Int,
        limit: Int,
        maxBytes: Int
    ): FsResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = Paths.get(absPath)
            if (!path.isRegularFile()) {
                return@runCatching FsResult.Err("No es un archivo regular: $absPath")
            }
            val total = path.fileSize()
            if (total > maxBytes) {
                return@runCatching FsResult.Err(
                    "El archivo pesa ${total} bytes y supera el límite de $maxBytes para lectura " +
                        "directa. Usa run_command con `rg`, `sed -n`, `head` o `tail` para extraer " +
                        "las partes que necesites."
                )
            }

            val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            // split con limit=-1 conserva las líneas vacías finales para que la
            // numeración coincida con un editor.
            val lines = text.split("\n")
            val totalLines = lines.size

            val start = offset.coerceAtLeast(1)
            if (start > totalLines) {
                return@runCatching FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("size", total)
                    put("totalLines", totalLines)
                    put("startLine", start)
                    put("endLine", start)
                    put("truncated", false)
                    put("content", "")
                    put("note", "offset $start está más allá del final del archivo ($totalLines líneas).")
                })
            }

            val safeLimit = limit.coerceAtLeast(1)
            // endExclusive en índice 0-based sobre `lines`.
            val endExclusive = (start - 1 + safeLimit).coerceAtMost(totalLines)
            val window = lines.subList(start - 1, endExclusive)

            val numbered = window.mapIndexed { i, line ->
                val n = start + i
                val shown = if (line.length > MAX_LINE_CHARS) {
                    line.take(MAX_LINE_CHARS) + "… [línea recortada, ${line.length} chars]"
                } else line
                "$n: $shown"
            }.joinToString("\n")

            val moreAfter = endExclusive < totalLines
            FsResult.Ok(buildJsonObject {
                put("success", true)
                put("path", absPath)
                put("size", total)
                put("totalLines", totalLines)
                put("startLine", start)
                put("endLine", endExclusive)
                put("truncated", moreAfter)
                put("content", numbered)
            })
        }.getOrElse { e -> FsResult.Err(e.message ?: "Error leyendo archivo") }
    }

    actual suspend fun readFileRaw(absPath: String, maxBytes: Int): FsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = Paths.get(absPath)
                if (!path.isRegularFile()) {
                    return@runCatching FsResult.Err("No es un archivo regular: $absPath")
                }
                val total = path.fileSize()
                if (total > maxBytes) {
                    return@runCatching FsResult.Err(
                        "El archivo pesa $total bytes y supera el límite de $maxBytes para edición."
                    )
                }
                val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("size", total)
                    put("content", text)
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

    actual suspend fun searchFiles(
        absPath: String,
        pattern: String,
        literal: Boolean,
        caseSensitive: Boolean,
        fileGlob: String?,
        maxResults: Int,
        workspaceRoot: String?
    ): FsResult = withContext(Dispatchers.IO) {
        runCatching {
            val root = Paths.get(absPath)
            if (!root.isDirectory()) {
                return@runCatching FsResult.Err("No es un directorio: $absPath")
            }

            // Regex por defecto; si no compila, degradar a literal en vez de fallar
            // (la regex inválida es el error más común del modelo con esta tool).
            var mode = if (literal) "literal" else "regex"
            val regex: Regex = if (literal) {
                Regex(Regex.escape(pattern), setOfNotNull(RegexOption.IGNORE_CASE.takeIf { !caseSensitive }))
            } else {
                runCatching {
                    Regex(pattern, setOfNotNull(RegexOption.IGNORE_CASE.takeIf { !caseSensitive }))
                }.getOrElse {
                    mode = "literal_fallback"
                    Regex(Regex.escape(pattern), setOfNotNull(RegexOption.IGNORE_CASE.takeIf { !caseSensitive }))
                }
            }

            val globMatcher = fileGlob?.takeIf { it.isNotBlank() }?.let {
                runCatching { root.fileSystem.getPathMatcher("glob:$it") }
                    .getOrElse { return@runCatching FsResult.Err("file_glob inválido: $fileGlob") }
            }

            val wsRoot = workspaceRoot?.let { Paths.get(it).normalize().absolute() }
            val cap = maxResults.coerceIn(1, 500)
            val matches = mutableListOf<String>()
            var filesScanned = 0
            var filesVisited = 0
            var truncated = false

            Files.walk(root).use { stream ->
                val iter = stream.iterator()
                while (iter.hasNext()) {
                    val p = iter.next()
                    if (p.isDirectory()) continue
                    // Poda por nombre de directorio en cualquier segmento del path.
                    if ((0 until p.nameCount).any { p.getName(it).toString() in SEARCH_EXCLUDED_DIRS }) continue
                    if (++filesVisited > SEARCH_MAX_FILES) { truncated = true; break }
                    if (globMatcher != null && !globMatcher.matches(p.fileName)) continue
                    if (!p.isRegularFile()) continue
                    if (runCatching { p.fileSize() }.getOrDefault(Long.MAX_VALUE) > SEARCH_MAX_FILE_BYTES) continue

                    val bytes = runCatching { Files.readAllBytes(p) }.getOrNull() ?: continue
                    // Heurística binario: byte NUL en los primeros 8KB.
                    val probe = minOf(bytes.size, 8192)
                    var isBinary = false
                    for (i in 0 until probe) {
                        if (bytes[i] == 0.toByte()) { isBinary = true; break }
                    }
                    if (isBinary) continue

                    filesScanned++
                    val display = if (wsRoot != null && p.normalize().absolute().startsWith(wsRoot)) {
                        wsRoot.relativize(p.normalize().absolute()).toString()
                    } else {
                        p.toString()
                    }

                    var perFile = 0
                    val lines = String(bytes, StandardCharsets.UTF_8).split("\n")
                    for ((i, line) in lines.withIndex()) {
                        if (!regex.containsMatchIn(line)) continue
                        val shown = if (line.length > MAX_LINE_CHARS) {
                            line.take(MAX_LINE_CHARS) + "… [línea recortada]"
                        } else line
                        matches.add("$display:${i + 1}: ${shown.trim()}")
                        if (++perFile >= SEARCH_MAX_PER_FILE) break
                        if (matches.size >= cap) break
                    }
                    if (matches.size >= cap) { truncated = truncated || iter.hasNext(); break }
                }
            }

            FsResult.Ok(buildJsonObject {
                put("success", true)
                put("matchCount", matches.size)
                put("filesScanned", filesScanned)
                put("truncated", truncated)
                put("mode", mode)
                put("matches", buildJsonArray { matches.forEach { add(JsonPrimitive(it)) } })
                if (matches.isEmpty()) {
                    put("note", "Sin coincidencias. Prueba con un patrón más corto o case_sensitive=false.")
                }
            })
        }.getOrElse { e -> FsResult.Err(e.message ?: "Error buscando en archivos") }
    }

    actual suspend fun editFile(
        absPath: String,
        oldString: String?,
        newString: String,
        replaceAll: Boolean,
        startLine: Int?,
        endLine: Int?
    ): FsResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = Paths.get(absPath)
            if (!path.isRegularFile()) {
                return@runCatching FsResult.Err("No es un archivo regular: $absPath")
            }

            val original = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

            if (startLine != null) {
                // ── Modo líneas ──────────────────────────────────────────────────
                val lines = original.split("\n")
                val totalLines = lines.size
                val start = startLine.coerceAtLeast(1)
                if (start > totalLines) {
                    return@runCatching FsResult.Err(
                        "start_line $start está más allá del final del archivo ($totalLines líneas)."
                    )
                }
                val end = (endLine ?: start).coerceAtLeast(start).coerceAtMost(totalLines)
                val before = lines.subList(0, start - 1)
                val after = lines.subList(end, totalLines)
                val updated = (before + newString.split("\n") + after).joinToString("\n")
                val bytes = updated.toByteArray(StandardCharsets.UTF_8)
                Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("mode", "line_range")
                    put("replacedLines", end - start + 1)
                    put("startLine", start)
                    put("endLine", end)
                    put("bytesWritten", bytes.size)
                })
            } else {
                // ── Modo string ──────────────────────────────────────────────────
                val old = oldString
                    ?: return@runCatching FsResult.Err(
                        "Debes proporcionar 'old_string' o 'start_line'/'end_line'."
                    )
                if (old.isEmpty()) {
                    return@runCatching FsResult.Err("'old_string' no puede estar vacío")
                }
                if (old == newString) {
                    return@runCatching FsResult.Err("'old_string' y 'new_string' son idénticos")
                }

                // 1) Match exacto (rápido, semántica precisa).
                val occurrences = countOccurrences(original, old)
                if (occurrences > 1 && !replaceAll) {
                    return@runCatching FsResult.Err(
                        "'old_string' aparece $occurrences veces. Amplía el texto para hacerlo único, " +
                            "pasa replace_all=true para reemplazar todas las ocurrencias, " +
                            "o usa start_line/end_line para apuntar al bloque exacto."
                    )
                }
                if (occurrences >= 1) {
                    val updated = original.replace(old, newString)
                    val bytes = updated.toByteArray(StandardCharsets.UTF_8)
                    Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    return@runCatching FsResult.Ok(buildJsonObject {
                        put("success", true)
                        put("path", absPath)
                        put("mode", "string_replace")
                        put("replacements", occurrences)
                        put("bytesWritten", bytes.size)
                    })
                }

                // 2) Fallback tolerante a whitespace: el modelo suele cambiar la
                // indentación o dejar espacios al copiar, y el match exacto falla.
                // Comparamos línea a línea ignorando espacios al inicio/fin; si hay
                // UN único bloque coincidente, lo reemplazamos re-indentando new_string
                // con la indentación real del archivo.
                when (val flex = findFlexibleMatch(original, old, newString)) {
                    is FlexResult.Unique -> {
                        val updated = original.substring(0, flex.start) + flex.replacement +
                            original.substring(flex.end)
                        val bytes = updated.toByteArray(StandardCharsets.UTF_8)
                        Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                        FsResult.Ok(buildJsonObject {
                            put("success", true)
                            put("path", absPath)
                            put("mode", "string_replace_flexible")
                            put("replacements", 1)
                            put("note", "Coincidencia hallada ignorando diferencias de espacios/indentación.")
                            put("bytesWritten", bytes.size)
                        })
                    }
                    is FlexResult.Multiple -> FsResult.Err(
                        "Ignorando espacios, 'old_string' coincide con ${flex.count} bloques distintos. " +
                            "Amplía el texto para hacerlo único o usa start_line/end_line."
                    )
                    FlexResult.None -> FsResult.Err(
                        "'old_string' no aparece en el archivo. Lee el archivo de nuevo y copia " +
                            "el texto exacto sin el prefijo 'N: ' de los números de línea. " +
                            "Si el match sigue fallando, usa start_line/end_line con los números exactos, " +
                            "o llama a read_tool_docs para ver la guía de edit_file." +
                            buildNearbyHint(original, old)
                    )
                }
            }
        }.getOrElse { e -> FsResult.Err(e.message ?: "Error editando archivo") }
    }

    actual suspend fun multiEditFile(absPath: String, edits: List<MultiFileEdit>): FsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = Paths.get(absPath)
                if (!path.isRegularFile()) {
                    return@runCatching FsResult.Err("No es un archivo regular: $absPath")
                }
                val original = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

                // Aplicar cada edición en memoria secuencialmente; abortar si alguna falla.
                var running = original
                edits.forEachIndexed { i, edit ->
                    if (edit.oldString.isEmpty()) {
                        return@runCatching FsResult.Err("Edición ${i + 1}: 'old_string' no puede estar vacío")
                    }
                    val count = countOccurrences(running, edit.oldString)
                    if (count == 0) {
                        when (val flex = findFlexibleMatch(running, edit.oldString, edit.newString)) {
                            is FlexResult.Unique -> {
                                running = running.substring(0, flex.start) + flex.replacement + running.substring(flex.end)
                                return@forEachIndexed
                            }
                            is FlexResult.Multiple -> return@runCatching FsResult.Err(
                                "Edición ${i + 1}: 'old_string' coincide con ${flex.count} bloques. Usa un texto más único."
                            )
                            FlexResult.None -> return@runCatching FsResult.Err(
                                "Edición ${i + 1}: 'old_string' no encontrado en el archivo."
                            )
                        }
                    } else if (count > 1 && !edit.replaceAll) {
                        return@runCatching FsResult.Err(
                            "Edición ${i + 1}: 'old_string' aparece $count veces. Amplía el texto o usa replace_all=true."
                        )
                    }
                    running = running.replace(edit.oldString, edit.newString)
                }

                val diff = buildEditsDiff(original, running)
                val bytes = running.toByteArray(StandardCharsets.UTF_8)
                Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                FsResult.Ok(buildJsonObject {
                    put("success", true)
                    put("path", absPath)
                    put("editsApplied", edits.size)
                    put("bytesWritten", bytes.size)
                    put("diff", diff)
                })
            }.getOrElse { e -> FsResult.Err(e.message ?: "Error en multi_edit") }
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

        // search_files: exclusiones y techos para no inflar contexto ni colgarse
        // en workspaces enormes.
        val SEARCH_EXCLUDED_DIRS = setOf(
            ".git", "build", "node_modules", ".gradle", ".idea",
            "dist", "target", ".venv", "venv", "__pycache__"
        )
        const val SEARCH_MAX_FILES = 20_000
        const val SEARCH_MAX_FILE_BYTES = 1_000_000L
        const val SEARCH_MAX_PER_FILE = 20

        /** Tope por línea al leer archivos: evita que una línea minificada infle el contexto. */
        const val MAX_LINE_CHARS = 2_000

        /** Secuencias de escape ANSI: ESC [ … m , ESC ] … , ESC c, etc. */
        val ANSI_PATTERN = Regex("""\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\x07]*\x07)""")

        /** Caracteres de control ilegales en XML 1.0 (excepto \t \n \r). */
        val CONTROL_CHARS_PATTERN = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F￾￿]""")
    }
}

// ── Helpers de edit_file (matching tolerante a whitespace) ───────────────────

private sealed interface FlexResult {
    /** Único bloque coincidente: [start, end) en chars del original, ya re-indentado. */
    data class Unique(val start: Int, val end: Int, val replacement: String) : FlexResult
    /** Varios bloques coinciden tras ignorar espacios — ambiguo, no editamos. */
    data class Multiple(val count: Int) : FlexResult
    /** Ningún bloque coincide ni siquiera ignorando espacios. */
    data object None : FlexResult
}

private fun countOccurrences(haystack: String, needle: String): Int {
    var count = 0
    var idx = haystack.indexOf(needle)
    while (idx >= 0) {
        count++
        idx = haystack.indexOf(needle, idx + needle.length)
    }
    return count
}

private fun leadingWhitespace(s: String): String = s.takeWhile { it == ' ' || it == '\t' }

/** Cap de líneas que mostramos del archivo en el hint de error, para no inflar el contexto. */
private const val HINT_MAX_LINES = 25

/**
 * Busca [old] en [original] comparando línea a línea tras `trim()` (ignora espacios
 * al inicio y al final). Si hay exactamente un bloque, devuelve su span en chars y
 * re-indenta [newString] usando la indentación REAL del archivo (no la que mandó el
 * modelo), de modo que la edición conserve el estilo del fichero.
 */
private fun findFlexibleMatch(original: String, old: String, newString: String): FlexResult {
    val origLines = original.split("\n")
    val oldLines = old.split("\n")
    val n = oldLines.size
    if (n == 0 || n > origLines.size) return FlexResult.None

    // Offset en chars donde empieza cada línea (la última entrada sobra +1, no se indexa).
    val offsets = IntArray(origLines.size + 1)
    for (i in origLines.indices) offsets[i + 1] = offsets[i] + origLines[i].length + 1

    val windows = mutableListOf<Int>()
    for (w in 0..(origLines.size - n)) {
        var matches = true
        for (k in 0 until n) {
            if (origLines[w + k].trim() != oldLines[k].trim()) {
                matches = false
                break
            }
        }
        if (matches) windows.add(w)
    }
    if (windows.isEmpty()) return FlexResult.None
    if (windows.size > 1) return FlexResult.Multiple(windows.size)

    val w = windows.first()
    val start = offsets[w]
    val end = (offsets[w + n] - 1).coerceIn(start, original.length)

    // Re-indentación: tomamos la base de indentación de la primera línea no vacía del
    // bloque, tanto en el archivo como en el old_string del modelo, y trasladamos esa
    // diferencia a cada línea de new_string.
    val refIdx = oldLines.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0)
    val oldBase = leadingWhitespace(oldLines[refIdx])
    val fileBase = leadingWhitespace(origLines[w + refIdx])
    val replacement = if (oldBase == fileBase) {
        newString
    } else {
        newString.split("\n").joinToString("\n") { line ->
            if (line.isBlank()) line
            else fileBase + if (line.startsWith(oldBase)) line.removePrefix(oldBase) else line
        }
    }
    return FlexResult.Unique(start, end, replacement)
}

/**
 * Cuando ningún match funciona, localiza la región del archivo más parecida a [old]
 * (más líneas coincidentes tras `trim()`) y la muestra con números de línea 1-based,
 * para que el modelo pueda reintentar con start_line/end_line exactos.
 */
private fun buildNearbyHint(original: String, old: String): String {
    val origLines = original.split("\n")
    val oldLines = old.split("\n")
    val n = oldLines.size
    if (n == 0 || origLines.size < n) return ""

    var bestW = 0
    var bestScore = -1
    for (w in 0..(origLines.size - n)) {
        var score = 0
        for (k in 0 until n) if (origLines[w + k].trim() == oldLines[k].trim()) score++
        if (score > bestScore) {
            bestScore = score
            bestW = w
        }
    }
    if (bestScore <= 0) return ""

    val from = bestW
    val to = (bestW + n).coerceAtMost(origLines.size).coerceAtMost(from + HINT_MAX_LINES)
    val sb = StringBuilder("\nRegión más parecida (líneas ${from + 1}-$to):\n")
    for (i in from until to) sb.append("${i + 1}: ${origLines[i]}\n")
    return sb.toString()
}


// ── Diff básico por líneas para multi_edit ────────────────────────────────────

/**
 * Genera un diff unificado simplificado (sin LCS): marca bloques añadidos (+)
 * y borrados (-) comparando original vs final. Suficiente para el diálogo de
 * confirmación. Limita la salida a MAX_DIFF_LINES para no inflar el contexto.
 */
private fun buildEditsDiff(original: String, updated: String): String {
    val origLines = original.lines()
    val updLines = updated.lines()
    val out = StringBuilder()
    var i = 0
    var j = 0
    var diffLines = 0
    while ((i < origLines.size || j < updLines.size) && diffLines < MAX_DIFF_LINES) {
        val o = origLines.getOrNull(i)
        val u = updLines.getOrNull(j)
        when {
            o == null -> { out.appendLine("+ $u"); j++; diffLines++ }
            u == null -> { out.appendLine("- $o"); i++; diffLines++ }
            o == u -> { i++; j++ }
            else -> {
                // Busca la siguiente coincidencia en una ventana pequeña
                val lookahead = 6
                val nextO = (1..lookahead).firstOrNull { updLines.getOrNull(j + it) == o }
                val nextU = (1..lookahead).firstOrNull { origLines.getOrNull(i + it) == u }
                when {
                    nextO != null && (nextU == null || nextO <= nextU) -> {
                        repeat(nextO) { out.appendLine("+ ${updLines.getOrNull(j + it)}"); diffLines++ }
                        j += nextO
                    }
                    nextU != null -> {
                        repeat(nextU) { out.appendLine("- ${origLines.getOrNull(i + it)}"); diffLines++ }
                        i += nextU
                    }
                    else -> { out.appendLine("- $o"); out.appendLine("+ $u"); i++; j++; diffLines += 2 }
                }
            }
        }
    }
    if (diffLines >= MAX_DIFF_LINES) out.appendLine("… (diff truncado)")
    return out.toString().trimEnd()
}

private const val MAX_DIFF_LINES = 200
