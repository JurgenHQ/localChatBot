package com.localchatbot.core.index

import java.io.File

actual class SemanticIndexStore {
    // Hermano de skills/, tools.md, memory.md y hooks.json.
    private val baseDir = File(System.getProperty("user.home"), ".localchatbot")
    private val indexDir = File(baseDir, "semantic-index")

    actual val isAvailable: Boolean = true

    actual fun collectFiles(workspace: String, maxFiles: Int): List<WorkspaceFile> = runCatching {
        val root = File(workspace)
        if (!root.isDirectory) return emptyList()
        // Sin canonicalizar: los hijos se enumeran a partir de `root`, así que el prefijo a
        // recortar tiene que ser exactamente ese path (canonicalizar rompería el recorte si
        // el workspace pasa por un symlink).
        val rootPath = root.path.trimEnd(File.separatorChar)
        val out = mutableListOf<WorkspaceFile>()
        // Recorrido iterativo con pila: `walkTopDown` no permite podar directorios sin
        // visitarlos, y saltarse node_modules es justamente lo que hace viable el indexado.
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < maxFiles) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (out.size >= maxFiles) break
                val name = child.name
                if (child.isDirectory) {
                    // Además de la lista negra, se saltan todas las carpetas ocultas: nada de
                    // lo que hay en .github/.vscode/.cache ayuda a "dónde se maneja X".
                    if (name !in INDEX_SKIP_DIRS && !name.startsWith(".")) stack.addLast(child)
                    continue
                }
                if (!child.isFile) continue
                if (child.extension.lowercase() !in INDEXABLE_EXTENSIONS) continue
                val size = child.length()
                if (size <= 0L || size > INDEX_MAX_FILE_BYTES) continue
                val rel = child.path.removePrefix(rootPath).removePrefix(File.separator)
                out += WorkspaceFile(
                    relPath = rel.replace('\\', '/'),
                    absPath = child.path,
                    size = size,
                    modifiedEpochMs = child.lastModified()
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    actual fun readText(absPath: String): String? = runCatching {
        val bytes = File(absPath).readBytes()
        // Heurística de binario idéntica a la de search_files: un NUL en los primeros 8KB.
        val probe = minOf(bytes.size, 8192)
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return null
        }
        bytes.decodeToString()
    }.getOrNull()

    actual fun load(workspace: String): String? = runCatching {
        val f = fileFor(workspace)
        if (!f.exists()) null else f.readText()
    }.getOrNull()

    actual fun save(workspace: String, content: String): Boolean = runCatching {
        indexDir.mkdirs()
        // Escritura atómica: el índice se reescribe entero y puede pesar megas. Un corte a
        // mitad dejaría un JSON truncado que hay que descartar y reindexar desde cero.
        val target = fileFor(workspace)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(content)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    actual fun delete(workspace: String) {
        runCatching { fileFor(workspace).delete() }
    }

    private fun fileFor(workspace: String) = File(indexDir, "${workspaceIndexKey(workspace)}.json")
}

actual fun createSemanticIndexStore(): SemanticIndexStore = SemanticIndexStore()
