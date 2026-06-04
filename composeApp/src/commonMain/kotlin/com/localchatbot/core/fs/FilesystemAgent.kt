package com.localchatbot.core.fs

import kotlinx.serialization.json.JsonObject

/**
 * Agente de filesystem y shell. Implementación real solo en desktop (JVM).
 * En Android/iOS los métodos lanzan [UnsupportedOperationException] — los tools
 * que lo usan reportan `isAvailable=false` en esas plataformas y nunca lo invocan.
 *
 * El acceso se hace mediante:
 *
 * 1. [resolveSafePath] valida una ruta de entrada contra el workspace y devuelve
 *    el path absoluto resuelto (o un error si está fuera y no se permite).
 * 2. Las operaciones (`createFile`, `readFile`, `runCommand`, etc.) reciben ya
 *    el path absoluto ya resuelto.
 *
 * La separación permite que las 5 tools compartan el mismo código de validación
 * sin duplicar lógica en cada una.
 */
expect class FilesystemAgent() {

    /**
     * Resuelve [input] como ruta absoluta normalizada relativa a [workspace].
     * Si [input] ya es absoluto se usa tal cual. La normalización elimina
     * `..` y `.` para obtener una forma canónica.
     *
     * Si la ruta resultante queda fuera de [workspace] (o si [workspace] es
     * null y [input] es absoluto), [allowOutside] decide si se admite o se
     * rechaza con un error.
     */
    fun resolveSafePath(workspace: String?, input: String, allowOutside: Boolean): SafePathResult

    /**
     * Crea un archivo en [absPath] con [content] (UTF-8). Si [overwrite] es false
     * y el archivo ya existe, retorna [FsResult.Err]. Crea los directorios padres
     * si hacen falta.
     */
    suspend fun createFile(absPath: String, content: String, overwrite: Boolean): FsResult

    /** Crea un directorio en [absPath] (incluyendo padres). */
    suspend fun createDirectory(absPath: String): FsResult

    /**
     * Lee el archivo en [absPath] como UTF-8. Trunca a [maxBytes] para no
     * inflar el contexto del modelo. Si el archivo es mayor el payload incluye
     * `truncated=true`.
     */
    suspend fun readFile(absPath: String, maxBytes: Int = 200_000): FsResult

    /** Lista las entradas (no recursivo) del directorio en [absPath]. */
    suspend fun listDirectory(absPath: String): FsResult

    /**
     * Ejecuta [command] en una shell con [workingDir] como cwd.
     *
     * Si [background] es false (default): bloquea hasta que el proceso termina o
     * expira [timeoutSeconds], y devuelve stdout/stderr/exitCode completos.
     *
     * Si [background] es true: lanza el proceso, espera hasta [startupCheckSeconds]
     * para capturar la salida inicial (útil para ver el puerto que levanta un servidor,
     * o un error de arranque), y retorna con `background=true` y el PID. El proceso
     * sigue corriendo en segundo plano. Para detenerlo usar `run_command` con
     * `kill <pid>`.
     */
    suspend fun runCommand(
        command: String,
        workingDir: String,
        timeoutSeconds: Int = 30,
        background: Boolean = false,
        startupCheckSeconds: Int = 5
    ): FsResult
}

sealed interface SafePathResult {
    data class Ok(val absPath: String, val insideWorkspace: Boolean) : SafePathResult
    data class Err(val message: String) : SafePathResult
}

sealed interface FsResult {
    data class Ok(val payload: JsonObject) : FsResult
    data class Err(val message: String) : FsResult
}
