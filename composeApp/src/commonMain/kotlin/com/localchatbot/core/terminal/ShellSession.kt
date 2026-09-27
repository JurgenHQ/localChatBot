package com.localchatbot.core.terminal

/**
 * Shell **persistente** contra la que escribe el usuario desde la terminal integrada.
 *
 * A diferencia de `FilesystemAgent.runCommand` (un proceso nuevo por comando, que es lo que
 * usa el agente), aquí el proceso vive entre comandos: `cd`, `export` y cualquier estado del
 * shell se conservan, igual que en una terminal de verdad.
 *
 * **No es una PTY.** El proceso arranca con stdin/stdout por pipes, no por un terminal, así
 * que no hay colores, ni control de trabajos, ni programas interactivos (vim, top, ssh con
 * prompt). Lo que sí funciona es el 99% del uso real: builds, tests, git, scripts.
 *
 * Implementación real solo en desktop; en móvil [createShellSession] devuelve null y la UI
 * de la terminal ni se ofrece.
 */
interface ShellSession {

    /** Directorio de trabajo actual del shell (se actualiza tras cada `cd`). */
    val workingDir: String

    /** False cuando el proceso murió (el usuario hizo `exit`, o el shell se cayó). */
    val isAlive: Boolean

    /**
     * Ejecuta [command] y **suspende hasta que termina**, invocando [onLine] por cada línea
     * de salida (stdout y stderr mezclados, como en una terminal).
     *
     * Solo un comando a la vez por sesión: el llamador es quien serializa.
     */
    suspend fun run(command: String, onLine: (String) -> Unit): ShellRunResult

    /**
     * Mata los procesos hijos del shell (el comando en curso) dejando vivo el shell.
     * Equivalente aproximado a Ctrl+C; sin PTY no se puede mandar SIGINT al job.
     */
    fun interrupt()

    /** Termina el proceso del shell. */
    fun close()
}

/**
 * Resultado de un comando: su código de salida y el cwd **resultante** (que puede haber
 * cambiado si el comando hizo `cd`).
 *
 * [exitCode] es null cuando el shell murió antes de reportar el código — típicamente porque
 * el comando era `exit`, o porque el proceso se cayó.
 */
data class ShellRunResult(
    val exitCode: Int?,
    val workingDir: String,
    val shellDied: Boolean = false
)

/** Crea una shell persistente arrancada en [workingDir]. Null en plataformas sin shell. */
expect fun createShellSession(workingDir: String): ShellSession?
