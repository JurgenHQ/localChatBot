package com.localchatbot.presentation.features.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.SafePathResult
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FsEntry(
    val name: String,
    val isDir: Boolean,
    val path: String
)

data class EditorUiState(
    val workspaceRoot: String? = null,
    val currentDir: String? = null,
    val entries: List<FsEntry> = emptyList(),
    val openFilePath: String? = null,
    val openFileName: String? = null,
    val content: String = "",
    val dirty: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Línea a la que se debe hacer scroll (1-indexed). Se consume y pone a null tras el scroll. */
    val scrollToLine: Int? = null,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    /** Índices de inicio (char) de cada coincidencia de búsqueda en [content]. */
    val searchMatches: List<Int> = emptyList(),
    /** Índice en [searchMatches] de la coincidencia activa (-1 si ninguna). */
    val currentMatchIndex: Int = -1,
    /** Contenido tal como fue leído de disco; base para el diff de guardado. */
    val originalContent: String = "",
    /** Diff de guardado pendiente de confirmar; no nulo = diálogo visible. */
    val pendingDiff: String? = null,
    /** Modo preview de Markdown (solo aplica a .md / .markdown). */
    val previewMode: Boolean = false
) {
    val canGoUp: Boolean
        get() = workspaceRoot != null && currentDir != null && currentDir != workspaceRoot

    /** Ruta relativa al workspace para mostrar en el breadcrumb. */
    val relativeDir: String
        get() {
            val root = workspaceRoot ?: return currentDir.orEmpty()
            val cur = currentDir ?: return ""
            return if (cur == root) "/" else "/" + cur.removePrefix(root).trimStart('/', '\\')
        }
}

/**
 * Editor de texto ligero con explorador, restringido SIEMPRE al workspace
 * configurado ([PreferencesRepository.current].fsWorkspaceDir). Solo se usa en
 * desktop: llama a [FilesystemAgent] directo (acción explícita del usuario, sin
 * pasar por la confirmación de tools) y resuelve cada ruta con
 * `allowOutside = false` para no poder salir del workspace ni con `..`.
 */
class EditorViewModel(
    private val preferences: PreferencesRepository,
    private val agent: FilesystemAgent
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /**
     * Carga inicial: posiciona el explorador en la raíz del workspace.
     *
     * NO hace un reset total del estado: preserva cualquier archivo ya abierto
     * (p. ej. cuando se abre el editor por un click en una referencia del chat,
     * `openFile` corre justo antes de que la pantalla monte y dispare `onOpen`).
     */
    fun onOpen() {
        viewModelScope.launch {
            val root = preferences.current().fsWorkspaceDir
            if (root == null) {
                _state.update { it.copy(workspaceRoot = null) }
                return@launch
            }
            _state.update { it.copy(workspaceRoot = root, error = null) }
            loadDir(root)
        }
    }

    fun navigateTo(path: String) = viewModelScope.launch { loadDir(path) }

    fun goUp() {
        val cur = _state.value.currentDir ?: return
        val root = _state.value.workspaceRoot ?: return
        if (cur == root) return
        // Subir un nivel via resolveSafePath con "..": queda capado por el workspace.
        viewModelScope.launch { loadDir("$cur/..") }
    }

    private suspend fun loadDir(target: String) {
        val abs = resolve(target) ?: return
        _state.update { it.copy(loading = true, error = null) }
        when (val r = agent.listDirectory(abs)) {
            is FsResult.Ok -> {
                val entries = r.payload["entries"]?.jsonArray.orEmptyList().map { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.content.orEmpty()
                    val isDir = o["type"]?.jsonPrimitive?.content == "dir"
                    FsEntry(name = name, isDir = isDir, path = "$abs/$name")
                }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
                _state.update { it.copy(currentDir = abs, entries = entries, loading = false) }
            }
            is FsResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
        }
    }

    fun openFile(path: String, line: Int? = null) = viewModelScope.launch {
        val abs = resolve(path) ?: return@launch
        _state.update { it.copy(loading = true, error = null) }
        when (val r = agent.readFileRaw(abs)) {
            is FsResult.Ok -> {
                val text = r.payload["content"]?.jsonPrimitive?.content.orEmpty()
                _state.update {
                    it.copy(
                        openFilePath = abs,
                        openFileName = abs.substringAfterLast('/').substringAfterLast('\\'),
                        content = text,
                        originalContent = text,
                        dirty = false,
                        loading = false,
                        scrollToLine = line
                    )
                }
            }
            is FsResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
        }
    }

    fun clearScrollToLine() {
        _state.update { it.copy(scrollToLine = null) }
    }

    fun toggleSearch() {
        _state.update {
            val visible = !it.searchVisible
            it.copy(
                searchVisible = visible,
                searchQuery = if (!visible) "" else it.searchQuery,
                searchMatches = if (!visible) emptyList() else it.searchMatches,
                currentMatchIndex = if (!visible) -1 else it.currentMatchIndex
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _state.update {
            val matches = findMatches(it.content, query)
            it.copy(
                searchQuery = query,
                searchMatches = matches,
                currentMatchIndex = if (matches.isNotEmpty()) 0 else -1
            )
        }
    }

    fun nextMatch() {
        _state.update {
            val n = it.searchMatches.size
            if (n == 0) it else it.copy(currentMatchIndex = (it.currentMatchIndex + 1) % n)
        }
    }

    fun prevMatch() {
        _state.update {
            val n = it.searchMatches.size
            if (n == 0) it else it.copy(currentMatchIndex = ((it.currentMatchIndex - 1) + n) % n)
        }
    }

    private fun findMatches(content: String, query: String): List<Int> {
        if (query.isBlank()) return emptyList()
        val result = mutableListOf<Int>()
        val lower = content.lowercase()
        val q = query.lowercase()
        var idx = 0
        while (true) {
            val found = lower.indexOf(q, idx)
            if (found < 0) break
            result.add(found)
            idx = found + q.length.coerceAtLeast(1)
        }
        return result
    }

    fun onContentChange(text: String) {
        _state.update { it.copy(content = text, dirty = true) }
    }

    /**
     * Solicita guardar: calcula el diff y muestra el diálogo de confirmación.
     * Si no hay cambios o el diff está vacío guarda directamente.
     */
    fun requestSave() {
        val s = _state.value
        if (s.openFilePath == null || !s.dirty) return
        val diff = buildLineDiff(s.originalContent, s.content)
        if (diff.isBlank()) {
            // Contenido idéntico al del disco (solo whitespace trailing, etc.)
            viewModelScope.launch { doSave() }
        } else {
            _state.update { it.copy(pendingDiff = diff) }
        }
    }

    /** Confirma el diff y escribe el archivo. */
    fun confirmSave() {
        _state.update { it.copy(pendingDiff = null) }
        viewModelScope.launch { doSave() }
    }

    /** Descarta el diálogo de diff sin guardar. */
    fun cancelSave() {
        _state.update { it.copy(pendingDiff = null) }
    }

    fun togglePreviewMode() {
        _state.update { it.copy(previewMode = !it.previewMode) }
    }

    private suspend fun doSave() {
        val path = _state.value.openFilePath ?: return
        val newContent = _state.value.content
        _state.update { it.copy(loading = true, error = null) }
        when (val r = agent.createFile(path, newContent, overwrite = true)) {
            is FsResult.Ok -> _state.update {
                it.copy(dirty = false, loading = false, originalContent = newContent)
            }
            is FsResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
        }
    }

    fun save() = viewModelScope.launch { doSave() }

    /** Crea un archivo nuevo (vacío) en el directorio actual y lo abre. */
    fun createFile(name: String) = viewModelScope.launch {
        val dir = _state.value.currentDir ?: return@launch
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        val abs = resolve("$dir/$trimmed") ?: return@launch
        when (val r = agent.createFile(abs, "", overwrite = false)) {
            is FsResult.Ok -> {
                loadDir(dir)
                openFile(abs)
            }
            is FsResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    fun closeFile() {
        _state.update { it.copy(openFilePath = null, openFileName = null, content = "", dirty = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Resuelve siempre contra el workspace, sin permitir salir de él. */
    private suspend fun resolve(input: String): String? {
        val root = preferences.current().fsWorkspaceDir
        return when (val r = agent.resolveSafePath(workspace = root, input = input, allowOutside = false)) {
            is SafePathResult.Ok -> r.absPath
            is SafePathResult.Err -> {
                _state.update { it.copy(error = r.message) }
                null
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmptyList() = this ?: emptyList()
