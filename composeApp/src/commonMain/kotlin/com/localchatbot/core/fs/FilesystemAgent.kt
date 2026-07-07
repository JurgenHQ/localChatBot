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
 * La separación permite que las tools de fs/shell compartan el mismo código de validación
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

    /**
     * Escribe [bytes] (binario) en [absPath]. Si [overwrite] es false y el archivo
     * ya existe, retorna [FsResult.Err]. Crea los directorios padres si hacen falta.
     * Pensado para guardar imágenes generadas (PNG) y otros blobs sin pasar por la
     * codificación UTF-8 de [createFile].
     */
    suspend fun writeBytes(absPath: String, bytes: ByteArray, overwrite: Boolean): FsResult

    /** Crea un directorio en [absPath] (incluyendo padres). */
    suspend fun createDirectory(absPath: String): FsResult

    /**
     * Lee el archivo en [absPath] como UTF-8, paginado por líneas.
     *
     * Devuelve [limit] líneas a partir de la línea [offset] (1-based), con cada
     * línea prefijada por su número (`123: contenido`) para que el modelo pueda
     * navegar archivos grandes en ventanas en vez de tragar el archivo entero.
     * El payload incluye `totalLines`, `startLine`, `endLine` y `truncated`
     * (true si quedan más líneas después de la ventana). Líneas muy largas se
     * recortan para no inflar el contexto. [maxBytes] es solo el techo de
     * seguridad para cargar el archivo en memoria; si se supera el archivo se
     * considera demasiado grande y se sugiere usar `run_command`.
     */
    suspend fun readFile(
        absPath: String,
        offset: Int = 1,
        limit: Int = 2000,
        maxBytes: Int = 5_000_000
    ): FsResult

    /**
     * Lee el archivo en [absPath] como UTF-8 y devuelve su contenido **íntegro y
     * sin modificar** en `content` (sin numerar líneas ni recortar). Pensado para
     * el editor in-app, donde el texto debe poder guardarse de vuelta tal cual.
     * [maxBytes] es el techo de seguridad para cargarlo en memoria.
     */
    suspend fun readFileRaw(absPath: String, maxBytes: Int = 5_000_000): FsResult

    /** Lista las entradas (no recursivo) del directorio en [absPath]. */
    suspend fun listDirectory(absPath: String): FsResult

    /**
     * Busca [pattern] en el contenido de los archivos bajo [absPath] (recursivo).
     *
     * [pattern] se interpreta como regex; si no compila, se degrada a búsqueda
     * literal (el payload lo indica en `mode`). Con [literal] true se fuerza la
     * búsqueda literal. [caseSensitive] false por defecto. [fileGlob] filtra por
     * nombre de archivo (p.ej. `*.kt`); null busca en todos.
     *
     * Excluye directorios pesados (.git, build, node_modules, …), archivos
     * binarios y mayores de 1MB. Devuelve como máximo [maxResults] matches en
     * formato `ruta:línea: texto`, con rutas relativas a [workspaceRoot] cuando
     * el archivo está dentro (para que los links `archivo.kt:42` del chat abran
     * el editor en esa línea).
     */
    suspend fun searchFiles(
        absPath: String,
        pattern: String,
        literal: Boolean = false,
        caseSensitive: Boolean = false,
        fileGlob: String? = null,
        maxResults: Int = 100,
        workspaceRoot: String? = null
    ): FsResult

    /**
     * Edita el archivo en [absPath]. Dos modos:
     *
     * **Modo string** ([oldString] != null): reemplaza [oldString] por [newString].
     * [oldString] debe coincidir exactamente (whitespace incluido) y aparecer una
     * sola vez a menos que [replaceAll] sea true.
     *
     * **Modo líneas** ([startLine] != null): reemplaza las líneas [startLine]–[endLine]
     * (1-based, inclusivo) por [newString]. No requiere reproducir el texto antiguo —
     * ideal para archivos grandes donde el match exacto es frágil.
     *
     * Exactamente uno de los dos modos debe estar activo.
     */
    suspend fun editFile(
        absPath: String,
        oldString: String? = null,
        newString: String,
        replaceAll: Boolean = false,
        startLine: Int? = null,
        endLine: Int? = null
    ): FsResult

    /**
     * Aplica [edits] (modo string) de forma atómica: valida todos secuencialmente
     * en memoria y escribe UNA sola vez si todos tienen éxito. Si alguno falla,
     * retorna error sin tocar el archivo.
     * Devuelve `diff` (líneas con `+`/`-`) en el payload de éxito para el diálogo
     * de confirmación.
     */
    suspend fun multiEditFile(absPath: String, edits: List<MultiFileEdit>): FsResult

    /**
     * Elimina el archivo en [absPath]. Si es un directorio, solo lo elimina
     * cuando está vacío o cuando [recursive] es true (borra todo su contenido).
     */
    suspend fun deletePath(absPath: String, recursive: Boolean): FsResult

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

/** Un único reemplazo dentro de un `multi_edit`. Solo modo string (no modo líneas). */
data class MultiFileEdit(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false
)

sealed interface SafePathResult {
    data class Ok(val absPath: String, val insideWorkspace: Boolean) : SafePathResult
    data class Err(val message: String) : SafePathResult
}

sealed interface FsResult {
    data class Ok(val payload: JsonObject) : FsResult
    data class Err(val message: String) : FsResult
}
