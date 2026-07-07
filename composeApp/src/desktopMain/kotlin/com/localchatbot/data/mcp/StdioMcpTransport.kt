package com.localchatbot.data.mcp

import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

actual fun createStdioMcpTransport(
    command: String,
    args: List<String>,
    env: Map<String, String>,
    json: Json,
    inspector: NetworkInspector?
): McpTransportLayer? = StdioMcpTransport(command, args, env, json, inspector)

/**
 * Transporte MCP **stdio**: lanza el servidor como proceso local y habla JSON-RPC
 * 2.0 newline-delimited por stdin/stdout (spec MCP stdio). stderr del proceso es
 * log libre y se descarta (capado) sin romper el framing.
 *
 * - El proceso se lanza vía la shell de login del usuario (`$SHELL -l -c "exec …"`)
 *   para heredar el PATH de nvm/homebrew — imprescindible para `npx`/`uvx`, los
 *   launchers típicos de servidores MCP. El `exec` reemplaza la shell por el server
 *   real, de modo que `destroy()` mata al proceso correcto. Sin `-i`: una shell
 *   interactiva ensucia stdout con rc/prompts, fatal en un protocolo line-delimited.
 * - Las respuestas se correlacionan por `id` contra deferreds pendientes; líneas
 *   no-JSON o sin `id` (servers que loguean a stdout, notificaciones del server)
 *   se ignoran. Los timeouts los pone [McpClient] (init 10 s / call 30 s).
 */
class StdioMcpTransport(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) : McpTransportLayer {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var nextId = 1
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private var closed = false

    /** Arranque lazy: el proceso se lanza en el primer request (espejo del lazy-connect del provider). */
    private fun ensureStartedLocked(): Process {
        process?.takeIf { it.isAlive }?.let { return it }
        check(!closed) { "Transporte stdio cerrado" }

        val fullCommand = buildString {
            append(quote(command))
            args.forEach { append(' '); append(quote(it)) }
        }
        val shellArgs = if (isWindows) {
            arrayOf("cmd", "/c", fullCommand)
        } else {
            val userShell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
            arrayOf(userShell, "-l", "-c", "exec $fullCommand")
        }

        val proc = ProcessBuilder(*shellArgs)
            .redirectErrorStream(false)
            .also { it.environment().putAll(env) }
            .start()
        process = proc
        writer = proc.outputStream.bufferedWriter()

        // Reader de stdout en daemon thread: parsea cada línea como JSON-RPC y
        // completa el deferred pendiente que coincida por id.
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { reader -> readLoop(reader) }
            }
            onProcessDead(proc)
        }.apply { isDaemon = true; name = "mcp-stdio-out" }.start()

        // stderr = log libre del server; drenar para que no bloquee el proceso.
        Thread {
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines ->
                    var logged = 0
                    lines.forEach { if (logged < MAX_STDERR_LINES) { println("[mcp-stdio:$command] $it"); logged++ } }
                }
            }
        }.apply { isDaemon = true; name = "mcp-stdio-err" }.start()

        return proc
    }

    private fun readLoop(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val id = runCatching {
                json.parseToJsonElement(line).jsonObject["id"]?.jsonPrimitive?.intOrNull
            }.getOrNull() ?: continue // no-JSON (log a stdout) o notificación del server: ignorar
            val deferred = synchronized(pending) { pending.remove(id) } ?: continue
            deferred.complete(line)
        }
    }

    private fun onProcessDead(proc: Process) {
        val exit = runCatching { proc.exitValue() }.getOrNull()
        val failures = synchronized(pending) {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        failures.forEach {
            it.completeExceptionally(IllegalStateException("El proceso MCP terminó (exit=${exit ?: "?"})"))
        }
    }

    override suspend fun sendRequest(method: String, params: JsonObject?): Result<String> = runCatching {
        val start = Clock.System.now().toEpochMilliseconds()
        val deferred: CompletableDeferred<String>
        val id: Int
        val requestJson: String

        mutex.withLock {
            val proc = ensureStartedLocked()
            id = nextId++
            deferred = CompletableDeferred()
            synchronized(pending) { pending[id] = deferred }
            requestJson = json.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)
            )
            runCatching {
                val w = writer ?: error("stdin no disponible")
                w.write(requestJson)
                w.write("\n")
                w.flush()
            }.onFailure {
                synchronized(pending) { pending.remove(id) }
                if (!proc.isAlive) onProcessDead(proc)
                throw IllegalStateException("No se pudo escribir al proceso MCP: ${it.message}")
            }
        }

        // El timeout lo pone McpClient (withTimeout alrededor de sendRequest).
        val payload = deferred.await()

        inspector?.record(
            NetworkTransaction(
                id = inspector.newId(),
                timestampEpochMs = start,
                method = method,
                url = "stdio://$command",
                kind = NetworkTransaction.Kind.McpCall,
                requestBody = requestJson,
                responseStatus = null,
                responseBody = payload.take(2000),
                durationMs = Clock.System.now().toEpochMilliseconds() - start
            )
        )

        val rpcResponse = json.decodeFromString(JsonRpcResponse.serializer(), payload)
        if (rpcResponse.error != null) {
            error("MCP error ${rpcResponse.error.code}: ${rpcResponse.error.message}")
        }
        payload
    }

    override suspend fun sendNotification(method: String, params: JsonObject?): Result<Unit> = runCatching {
        mutex.withLock {
            ensureStartedLocked()
            val body = json.encodeToString(JsonObject.serializer(), buildJsonRpcNotification(method, params))
            val w = writer ?: error("stdin no disponible")
            w.write(body)
            w.write("\n")
            w.flush()
        }
    }

    override suspend fun close() {
        mutex.withLock {
            closed = true
            val proc = process ?: return@withLock
            process = null
            runCatching { writer?.close() }
            writer = null
            // destroy() suele bastar (SIGTERM); forcibly como red de seguridad.
            runCatching {
                proc.destroy()
                if (!proc.waitFor(GRACE_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
            onProcessDead(proc)
        }
    }

    /** Quote naive para la shell: envuelve en comillas simples escapando las internas. */
    private fun quote(s: String): String =
        if (isWindows) s else "'" + s.replace("'", "'\\''") + "'"

    private companion object {
        const val GRACE_SHUTDOWN_SECONDS = 2L
        const val MAX_STDERR_LINES = 200
    }
}
