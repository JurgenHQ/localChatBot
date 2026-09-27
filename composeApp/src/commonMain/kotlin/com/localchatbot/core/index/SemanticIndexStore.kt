package com.localchatbot.core.index

/**
 * Archivo del workspace candidato a indexarse. [relPath] usa siempre `/` como separador
 * (también en Windows) para que el índice sea comparable entre sesiones y las rutas que
 * devuelve la tool sirvan como enlace en el chat.
 */
data class WorkspaceFile(
    val relPath: String,
    val absPath: String,
    val size: Long,
    val modifiedEpochMs: Long
)

/**
 * Acceso a disco del índice semántico: recorrido del workspace, lectura de archivos de
 * texto y persistencia del índice en `~/.localchatbot/semantic-index/<key>.json`
 * (mismo patrón de archivo que `hooks.json`, `memory.md` y `tools.md`; el porqué de no
 * usar SQLite está en [SemanticIndex]).
 *
 * Solo desktop tiene impl real; en móvil [isAvailable] es false y todo devuelve vacío.
 */
expect class SemanticIndexStore {
    val isAvailable: Boolean

    /**
     * Archivos de texto bajo [workspace], recursivo, ya filtrados: se saltan los
     * directorios pesados (.git, build, node_modules…), los binarios y lo que pase de
     * [INDEX_MAX_FILE_BYTES]. Se corta en [maxFiles] para que un workspace enorme no
     * dispare miles de llamadas al modelo de embeddings sin que el usuario lo pida.
     */
    fun collectFiles(workspace: String, maxFiles: Int = INDEX_MAX_FILES): List<WorkspaceFile>

    /** Contenido de un archivo del workspace, o null si no se puede leer como texto. */
    fun readText(absPath: String): String?

    /** JSON crudo del índice de [workspace], o null si no hay. */
    fun load(workspace: String): String?

    /** Persiste el JSON del índice de [workspace]. Devuelve false si falló la escritura. */
    fun save(workspace: String, content: String): Boolean

    /** Borra el índice de [workspace] (usado al forzar un reindexado completo). */
    fun delete(workspace: String)
}

expect fun createSemanticIndexStore(): SemanticIndexStore

/** Tope de archivos recorridos por indexado. */
const val INDEX_MAX_FILES: Int = 3_000

/** Archivos más grandes que esto se saltan (minificados, datos, lockfiles). */
const val INDEX_MAX_FILE_BYTES: Long = 400_000L

/**
 * Extensiones que se indexan. Lista blanca en vez de lista negra: el índice cuesta una
 * llamada al modelo por trozo, así que es mejor perderse un `.xyz` raro que embeber un
 * volcado de datos entero.
 */
val INDEXABLE_EXTENSIONS: Set<String> = setOf(
    "kt", "kts", "java", "swift", "m", "mm", "js", "jsx", "ts", "tsx", "py", "rb", "go",
    "rs", "c", "h", "cpp", "hpp", "cc", "cs", "php", "scala", "sh", "bash", "zsh", "ps1",
    "sql", "gradle", "properties", "toml", "yaml", "yml", "json", "xml", "html", "css",
    "scss", "md", "txt", "vue", "svelte", "dart", "lua", "ex", "exs", "r", "jl"
)

/** Directorios que nunca se recorren (mismos que la búsqueda por texto). */
val INDEX_SKIP_DIRS: Set<String> = setOf(
    ".git", "build", "node_modules", ".gradle", ".idea", "dist", "target",
    ".venv", "venv", "__pycache__", ".kotlin", "out", ".next", "vendor"
)
