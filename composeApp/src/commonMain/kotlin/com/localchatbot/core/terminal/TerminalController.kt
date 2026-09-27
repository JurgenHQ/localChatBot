package com.localchatbot.core.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Terminales integradas, **una por sesión de chat**.
 *
 * Que sean por sesión no es cosmético: una tarea programada o un sub-agente corren en
 * paralelo a la conversación que tengas abierta, así que un único buffer global mezclaría
 * la salida de dos trabajos distintos, y un único shell dejaría que el comando que escribes
 * tú y el de un turno automático se pisaran. Cada sesión tiene su buffer y su proceso.
 *
 * El buffer de una sesión existe desde que algo escribe en él (tú abriendo la terminal, o el
 * agente ejecutando un `run_command` en esa sesión); el **proceso** del shell no se lanza
 * hasta que envías tu primer comando, así que las sesiones que solo reflejan al agente no
 * cuestan un proceso.
 */
class TerminalController(
    private val scope: CoroutineScope,
    /** Workspace efectivo de una sesión: es el cwd con el que arranca su shell. */
    private val workspaceForSession: suspend (String) -> String?
) {
    private val terminals = MutableStateFlow<Map<String, SessionTerminal>>(emptyMap())

    /**
     * Terminal de [sessionId], creándola si hace falta. No suspende porque la llama la UI
     * durante la composición.
     *
     * El `updateAndGet` puede reejecutar el bloque si hay contención y construir un
     * [SessionTerminal] que se descarta; es inofensivo porque el constructor no lanza el
     * shell (eso pasa en el primer `submit`).
     */
    fun terminalFor(sessionId: String): SessionTerminal =
        terminals.updateAndGet { current ->
            if (current.containsKey(sessionId)) current
            else current + (sessionId to SessionTerminal(scope) { workspaceForSession(sessionId) })
        }.getValue(sessionId)

    /**
     * Terminal del **borrador**: la que se usa mientras no hay conversación activa (pantalla
     * de conversación nueva). La terminal no puede depender de haber empezado a chatear —
     * abrir el proyecto y lanzar un servidor es justo lo primero que uno quiere hacer.
     */
    fun scratch(): SessionTerminal = terminalFor(SCRATCH_ID)

    /**
     * Terminal de [sessionId], **adoptando la del borrador** si la sesión todavía no tiene la
     * suya. Sin esto, escribir `npm run dev` en la pantalla de conversación nueva y luego
     * enviar el primer mensaje te dejaría mirando una terminal vacía, con el servidor
     * corriendo en un buffer al que ya no se llega.
     *
     * La adoptada conserva el shell que ya arrancó (en el workspace global); la asignación a
     * proyecto de la conversación, si llega después, no lo mueve.
     */
    fun terminalForAdoptingScratch(sessionId: String): SessionTerminal =
        terminals.updateAndGet { current ->
            val scratch = current[SCRATCH_ID]
            when {
                current.containsKey(sessionId) -> current
                scratch != null -> current - SCRATCH_ID + (sessionId to scratch)
                else -> current + (sessionId to SessionTerminal(scope) { workspaceForSession(sessionId) })
            }
        }.getValue(sessionId)

    /** Cierra el shell y descarta el buffer de una sesión borrada. */
    fun dispose(sessionId: String) {
        val terminal = terminals.value[sessionId]
        terminals.update { it - sessionId }
        terminal?.close()
    }

    /** Cierra todos los shells (hook de apagado del desktop). */
    fun closeAll() {
        val all = terminals.value.values
        terminals.update { emptyMap() }
        all.forEach { it.close() }
    }

    private companion object {
        /** Clave reservada de la terminal del borrador; no es un id de sesión real. */
        const val SCRATCH_ID = "__scratch__"
    }
}

/** Tipo de línea del buffer; determina cómo se pinta y de dónde vino. */
enum class TerminalLineKind {
    /** Comando que escribiste tú. */
    UserCommand,

    /** Comando que lanzó el agente (espejo, no lo ejecuta esta terminal). */
    AgentCommand,

    /** Salida de un proceso. */
    Output,

    /** Mensajes de la propia terminal: código de salida, shell reiniciado, errores. */
    System
}

data class TerminalLine(
    val id: Long,
    val text: String,
    val kind: TerminalLineKind
)

/**
 * Buffer + shell de una sesión.
 *
 * Las líneas no se publican de una en una: se encolan y se vuelcan en lote cada
 * [FLUSH_INTERVAL_MS]. Un `./gradlew build` emite miles de líneas en segundos y un `update`
 * del [StateFlow] por línea recompondría la lista a ese ritmo — el mismo motivo por el que
 * el streaming del chat persiste a intervalos en vez de por token.
 */
class SessionTerminal internal constructor(
    private val scope: CoroutineScope,
    private val workspaceProvider: suspend () -> String?
) {
    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _workingDir = MutableStateFlow<String?>(null)
    val workingDir: StateFlow<String?> = _workingDir.asStateFlow()

    /** True mientras corre un comando **tuyo** (el del agente no bloquea esta terminal). */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val pending = Channel<Pair<TerminalLineKind, String>>(Channel.UNLIMITED)
    private var session: ShellSession? = null
    private var currentJob: Job? = null

    /** Serializa la creación del shell: [warmUp] y [submit] pueden coincidir. */
    private val sessionMutex = Mutex()

    init {
        scope.launch {
            // El id se asigna aquí y no en `append`: los appends llegan desde los hilos de
            // lectura de los procesos, y un contador compartido sin sincronizar podría dar
            // dos líneas con la misma key y hacer reventar la LazyColumn.
            var nextId = 0L
            val batch = mutableListOf<TerminalLine>()
            while (true) {
                val first = pending.receive()
                batch.add(TerminalLine(nextId++, first.second, first.first))
                // Deja que se acumule la ráfaga antes de publicar.
                delay(FLUSH_INTERVAL_MS)
                while (true) {
                    val next = pending.tryReceive().getOrNull() ?: break
                    batch.add(TerminalLine(nextId++, next.second, next.first))
                }
                val toAppend = batch.toList()
                batch.clear()
                _lines.update { (it + toAppend).takeLast(MAX_LINES) }
            }
        }
    }

    // ── Entrada del usuario ─────────────────────────────────────────────────

    /** Ejecuta [command] en el shell de esta sesión. Ignorado si ya hay uno corriendo. */
    fun submit(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || _running.value) return
        _history.update { ((it - cmd) + cmd).takeLast(MAX_HISTORY) }
        append(TerminalLineKind.UserCommand, cmd)
        _running.value = true
        currentJob = scope.launch {
            try {
                val shell = ensureSession()
                if (shell == null) {
                    append(TerminalLineKind.System, "No se pudo iniciar el shell.")
                    return@launch
                }
                val result = shell.run(cmd) { append(TerminalLineKind.Output, it) }
                _workingDir.value = result.workingDir
                when {
                    result.shellDied -> {
                        session = null
                        append(TerminalLineKind.System, "El shell terminó. El siguiente comando abrirá uno nuevo.")
                    }
                    result.exitCode != null && result.exitCode != 0 ->
                        append(TerminalLineKind.System, "exit ${result.exitCode}")
                }
            } finally {
                _running.value = false
            }
        }
    }

    /** Ctrl+C aproximado: mata el comando en curso dejando vivo el shell. */
    fun interrupt() {
        if (!_running.value) return
        session?.interrupt()
        append(TerminalLineKind.System, "^C")
    }

    /**
     * Mata el shell y arranca otro en el siguiente comando. Es la salida cuando la sesión
     * queda colgada — por ejemplo si escribiste una comilla sin cerrar y el shell se quedó
     * esperando el resto de la línea, que `interrupt` no arregla porque no hay proceso hijo.
     */
    fun restart() {
        currentJob?.cancel()
        session?.close()
        session = null
        _running.value = false
        _workingDir.value = null
        append(TerminalLineKind.System, "Shell reiniciado.")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun close() {
        currentJob?.cancel()
        session?.close()
        session = null
    }

    /**
     * Arranca el shell sin ejecutar nada. Lo llama el panel al abrirse para que el coste del
     * arranque (cargar `.zprofile`/`.zshrc`, que en un entorno con muchas integraciones puede
     * pasar del segundo) se pague mientras escribes, y no como si tu primer comando fuera
     * lento. Si ya hay shell, no hace nada.
     */
    fun warmUp() {
        if (session != null) return
        scope.launch { ensureSession() }
    }

    private suspend fun ensureSession(): ShellSession? = sessionMutex.withLock {
        session?.takeIf { it.isAlive }?.let { return@withLock it }
        // Sin workspace configurado la terminal sigue siendo útil: el shell arranca en el
        // home del usuario (lo resuelve la implementación) en vez de negarse a abrir.
        val dir = workspaceProvider().orEmpty()
        createShellSession(dir)?.also {
            session = it
            _workingDir.value = it.workingDir
        }
    }

    // ── Espejo de lo que ejecuta el agente ──────────────────────────────────
    //
    // Solo se muestra: el comando del agente corre en su propio proceso vía
    // `FilesystemAgent.runCommand`, con sus confirmaciones y su timeout, y nunca entra por
    // el stdin de este shell. Por eso puede llegar mientras tú tienes un comando corriendo
    // sin que ninguno de los dos se vea afectado.

    fun appendAgentCommand(command: String, cwd: String?) {
        append(TerminalLineKind.AgentCommand, command)
        if (cwd != null && cwd != _workingDir.value) {
            append(TerminalLineKind.System, "  (agente, cwd: $cwd)")
        }
    }

    /** Trozo de salida del proceso del agente, tal como llega del pipe (puede traer varias líneas). */
    fun appendAgentOutput(chunk: String) {
        if (chunk.isEmpty()) return
        chunk.split('\n').forEach { line ->
            val clean = line.trimEnd('\r')
            if (clean.isNotEmpty()) append(TerminalLineKind.Output, clean)
        }
    }

    fun appendAgentResult(text: String) = append(TerminalLineKind.System, text)

    private fun append(kind: TerminalLineKind, text: String) {
        pending.trySend(kind to text)
    }

    private companion object {
        /** Techo del buffer por sesión: es memoria viva, no historial persistido. */
        const val MAX_LINES = 3_000
        const val MAX_HISTORY = 100
        const val FLUSH_INTERVAL_MS = 60L
    }
}
