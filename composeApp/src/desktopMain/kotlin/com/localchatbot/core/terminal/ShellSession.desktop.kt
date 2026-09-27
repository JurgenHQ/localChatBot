package com.localchatbot.core.terminal

import com.localchatbot.core.text.ConsoleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.random.Random

actual fun createShellSession(workingDir: String): ShellSession? = runCatching {
    // Sin workspace (o con uno que ya no existe) se abre en el home, como haría cualquier
    // terminal. Negarse a abrir sería peor: no poder ni hacer `cd` hasta el proyecto.
    val dir = workingDir.takeIf { it.isNotBlank() && File(it).isDirectory }
        ?: System.getProperty("user.home")
    DesktopShellSession(dir)
}.getOrNull()

/**
 * Shell persistente sobre pipes, con **marcadores centinela** para delimitar cada comando.
 *
 * El problema que resuelven los marcadores: sin una PTY no hay forma de saber dónde termina
 * la salida de un comando y empieza la del siguiente, ni cuál fue su código de salida. Por
 * cada comando se escriben tres líneas en el stdin del shell:
 *
 * ```
 * printf '\n<START>\n'
 * { <comando del usuario>
 * } < /dev/null
 * printf '<END>:%s:%s\n' "$?" "$PWD"
 * ```
 *
 * y al leer se descarta todo lo anterior a `<START>` (banner de login, avisos del shell),
 * se emite lo que va en medio y se parsea de `<END>` el exit code y el cwd resultante — así
 * un `cd` se refleja en la UI sin tener que preguntar aparte.
 *
 * Detalles que no son evidentes:
 * - **El comando va dentro de `{ … }` en su propia línea**, no pegado con `;`, porque un
 *   comando que termina en `&` o en un comentario `#` rompería la línea. Y el grupo lleva
 *   `< /dev/null`: sin eso, un comando que lee stdin (`cat` sin argumentos) se comería las
 *   líneas de marcador que vienen detrás y la sesión quedaría colgada.
 * - **Los marcadores llevan un token aleatorio por sesión**, para que la salida de un
 *   comando que imprima el literal no pueda hacerse pasar por el fin del comando.
 * - **El shell arranca como login NO interactivo** (`-l`, sin `-i`). Con `-i` el shell
 *   imprime su prompt antes de leer cada línea, y ese prompt cae justo entre el marcador de
 *   inicio y la salida real: se vería la basura de starship/fig en medio de cada comando.
 *   El coste es que `.zshrc`/`.bashrc` no se cargan, así que se hacen `source` a mano en el
 *   arranque (silenciados) para heredar el PATH de nvm/fnm igual que hace `run_command`.
 */
private class DesktopShellSession(startDir: String) : ShellSession {

    private val token = Random.nextLong().toULong().toString(36)
    private val startMarker = "__LCB_S_${token}__"
    private val endMarker = "__LCB_E_${token}__"

    private val process: Process
    private val writer: BufferedWriter
    private val reader: BufferedReader

    @Volatile
    override var workingDir: String = startDir
        private set

    override val isAlive: Boolean get() = process.isAlive

    init {
        val args = if (isWindows) {
            // /q apaga el eco de comandos; sin eso cada línea que escribimos se vería.
            arrayOf("cmd.exe", "/q")
        } else {
            val userShell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
            arrayOf(userShell, "-l")
        }
        val builder = ProcessBuilder(*args)
            .directory(File(startDir))
            // Una terminal sin PTY no pinta colores; anunciarlo evita que las herramientas
            // los emitan y que el filtro ANSI tenga que trabajar por cada línea.
            .also { it.environment()["TERM"] = "dumb" }
            .redirectErrorStream(true)
        process = builder.start()
        writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
        writeInit()
    }

    /**
     * Comandos de arranque. Su salida no se lee nunca: se descarta junto con todo lo que
     * llegue antes del primer marcador de inicio.
     */
    private fun writeInit() = runCatching {
        if (isWindows) {
            // Sin esto, cmd escribe en la codepage del sistema y los acentos llegan rotos.
            writer.write("chcp 65001 > nul\r\n")
        } else {
            writer.write("[ -n \"\$ZSH_VERSION\" ] && [ -f \"\$HOME/.zshrc\" ] && source \"\$HOME/.zshrc\" >/dev/null 2>&1\n")
            writer.write("[ -n \"\$BASH_VERSION\" ] && [ -f \"\$HOME/.bashrc\" ] && source \"\$HOME/.bashrc\" >/dev/null 2>&1\n")
        }
        writer.flush()
    }

    override suspend fun run(command: String, onLine: (String) -> Unit): ShellRunResult =
        withContext(Dispatchers.IO) {
            if (!process.isAlive) return@withContext died()
            if (!runCatching { writeCommand(command) }.isSuccess) return@withContext died()
            readUntilEnd(onLine)
        }

    private fun readUntilEnd(onLine: (String) -> Unit): ShellRunResult {
        var started = false
        var emitted = 0
        while (true) {
            val raw = reader.readLine() ?: return died()
            val line = ConsoleText.sanitize(raw)
            // Línea que era solo secuencias de escape (los hooks del prompt las emiten
            // alrededor de cada comando): descartarla, o el buffer se llena de blancos.
            // Una línea vacía de verdad sí se conserva.
            if (line.isEmpty() && raw.isNotEmpty()) continue
            if (!started) {
                if (line.contains(startMarker)) started = true
                continue
            }
            val endAt = line.indexOf(endMarker)
            if (endAt < 0) {
                // El techo es por la UI, no por el shell: seguimos leyendo hasta el marcador
                // final (si dejáramos de leer, el pipe se llenaría y el proceso se
                // bloquearía), pero dejamos de volcar líneas en el buffer.
                if (emitted < MAX_LINES_PER_COMMAND) onLine(line)
                else if (emitted == MAX_LINES_PER_COMMAND) {
                    onLine("… salida truncada a $MAX_LINES_PER_COMMAND líneas")
                }
                emitted++
                continue
            }
            // El comando puede haber impreso sin salto final, y entonces el marcador queda
            // pegado al final de esa misma línea.
            val head = line.substring(0, endAt)
            if (head.isNotEmpty() && emitted < MAX_LINES_PER_COMMAND) onLine(head)
            return parseEnd(line.substring(endAt + endMarker.length))
        }
    }

    private fun writeCommand(command: String) {
        val eol = if (isWindows) "\r\n" else "\n"
        if (isWindows) {
            writer.write("echo $startMarker$eol")
            if (command.isNotBlank()) writer.write("$command$eol")
            writer.write("echo $endMarker:%errorlevel%:%cd%$eol")
        } else {
            writer.write("printf '\\n$startMarker\\n'\n")
            // Un comando en blanco haría `{ }`, que es error de sintaxis: se salta el grupo.
            if (command.isNotBlank()) writer.write("{ $command\n} < /dev/null\n")
            writer.write("printf '$endMarker:%s:%s\\n' \"\$?\" \"\$PWD\"\n")
        }
        writer.flush()
    }

    /** Formato de la cola del marcador: `:<exitCode>:<cwd>`. El cwd puede contener `:`. */
    private fun parseEnd(tail: String): ShellRunResult {
        val body = tail.removePrefix(":")
        val code = body.substringBefore(':').trim().toIntOrNull()
        val dir = body.substringAfter(':', missingDelimiterValue = "").trim()
        if (dir.isNotEmpty()) workingDir = dir
        return ShellRunResult(exitCode = code, workingDir = workingDir)
    }

    private fun died() = ShellRunResult(exitCode = null, workingDir = workingDir, shellDied = true)

    /**
     * Sin PTY no hay grupo de proceso al que mandar SIGINT, así que se matan los
     * descendientes del shell — que son exactamente el comando en curso y lo que él haya
     * lanzado. El shell sobrevive, imprime su marcador final y la sesión sigue usable.
     *
     * `destroy()` es SIGTERM y hay procesos que lo ignoran o tardan en cerrar (servidores de
     * desarrollo con su propio manejador de señales), así que un hilo aparte remata a los que
     * sigan vivos pasada la gracia: "detener" tiene que detener de verdad.
     */
    override fun interrupt() {
        val victims = runCatching { process.descendants().toList() }.getOrElse { return }
        victims.forEach { runCatching { it.destroy() } }
        Thread {
            Thread.sleep(INTERRUPT_GRACE_MS)
            victims.forEach { if (it.isAlive) runCatching { it.destroyForcibly() } }
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { process.destroy() }
        // La cortesía de los 2 s en un hilo aparte: `close()` se llama desde la UI.
        Thread {
            runCatching {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /** Techo de líneas volcadas a la UI por comando (un `find /` no debe colgar la app). */
        const val MAX_LINES_PER_COMMAND = 5_000

        /** Margen tras el SIGTERM antes de rematar al proceso que sigue vivo. */
        const val INTERRUPT_GRACE_MS = 2_000L
    }
}
