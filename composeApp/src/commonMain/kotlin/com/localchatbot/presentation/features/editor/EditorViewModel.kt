package com.localchatbot.presentation.features.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.SafePathResult
import com.localchatbot.core.image.decodeImage
import com.localchatbot.core.state.ActiveWorkspaceStore
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Extensiones que se muestran como preview de imagen en vez de texto plano. */
val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

data class FsEntry(
    val name: String,
    val isDir: Boolean,
    val path: String
)

/** Fila aplanada del árbol para renderizar en una única LazyColumn. */
data class TreeRow(
    val entry: FsEntry,
    val depth: Int
)

data class RenamePrompt(
    val path: String,
    val isDir: Boolean,
    val currentName: String,
    val error: String? = null
)

data class DeleteConfirm(
    val path: String,
    val isDir: Boolean,
    val name: String,
    val error: String? = null
)

data class NewEntryPrompt(
    val parentDir: String,
    val isDir: Boolean,
    val error: String? = null
)

data class EditorUiState(
    val workspaceRoot: String? = null,
    /** Caché de hijos ya cargados, por directorio absoluto (incluye la raíz). */
    val childrenByPath: Map<String, List<FsEntry>> = emptyMap(),
    val expandedPaths: Set<String> = emptySet(),
    /** Directorios cuyo listado está en curso (muestra spinner en su fila). */
    val loadingPaths: Set<String> = emptySet(),
    /** Entrada seleccionada en el árbol (distinta del archivo abierto), para F2/Delete. */
    val selectedPath: String? = null,
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
    val previewMode: Boolean = false,
    /** No nulo cuando el archivo abierto es una imagen decodificada (preview de solo lectura). */
    val imageBitmap: ImageBitmap? = null,
    val renamePrompt: RenamePrompt? = null,
    val deleteConfirm: DeleteConfirm? = null,
    val newEntryPrompt: NewEntryPrompt? = null,
    /** Posición del cursor dentro de [content], reportada por el campo de texto. */
    val cursorOffset: Int = 0,
    /** Sugerencia de autocompletado pendiente de aceptar (texto fantasma). */
    val suggestion: String? = null,
    /**
     * Offset del cursor al que pertenece [suggestion]. La UI compara este valor con su
     * cursor real antes de dibujar: si no coinciden, la sugerencia quedó desfasada y no
     * debe pintarse en un sitio que no le corresponde.
     */
    val suggestionAnchor: Int = -1,
    /** Hay una petición de autocompletado en vuelo. */
    val suggestionLoading: Boolean = false,
    /** Espejo de `AppPreferences.codeCompletionEnabled`. */
    val codeCompletionEnabled: Boolean = false
) {
    val rootEntries: List<FsEntry>
        get() = workspaceRoot?.let { childrenByPath[it] }.orEmpty()

    /** Aplana el árbol (raíz + subcarpetas expandidas) en una lista con profundidad, para una sola LazyColumn. */
    val visibleRows: List<TreeRow>
        get() {
            val result = mutableListOf<TreeRow>()
            fun walk(entries: List<FsEntry>, depth: Int) {
                for (entry in entries) {
                    result.add(TreeRow(entry, depth))
                    if (entry.isDir && entry.path in expandedPaths) {
                        childrenByPath[entry.path]?.let { walk(it, depth + 1) }
                    }
                }
            }
            walk(rootEntries, 0)
            return result
        }

    val selectedEntry: FsEntry?
        get() {
            val path = selectedPath ?: return null
            return (rootEntries + childrenByPath.values.flatten()).firstOrNull { it.path == path }
        }
}

/**
 * Editor de texto ligero con explorador en árbol, restringido SIEMPRE al workspace
 * efectivo de la sesión activa ([ActiveWorkspaceStore.current] — el proyecto asignado,
 * o el `fsWorkspaceDir` global si no hay proyecto). Solo se usa en desktop: llama a
 * [FilesystemAgent] directo (acción explícita del usuario, sin pasar por la
 * confirmación de tools) y resuelve cada ruta con `allowOutside = false` para no
 * poder salir del workspace ni con `..`.
 *
 * El árbol se carga de forma perezosa: cada carpeta lee su contenido recién cuando
 * se expande por primera vez, y el resultado queda cacheado en [EditorUiState.childrenByPath]
 * hasta que se pide un refresh explícito.
 */
class EditorViewModel(
    private val activeWorkspaceStore: ActiveWorkspaceStore,
    private val agent: FilesystemAgent,
    private val modelRepository: ModelRepository,
    private val preferences: PreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Petición de autocompletado en curso (debounce incluido). Se cancela en cada tecla. */
    private var completionJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.preferences.collect { prefs ->
                _state.update { it.copy(codeCompletionEnabled = prefs.codeCompletionEnabled) }
            }
        }
    }

    /**
     * El VM no alcanza el portapapeles (solo existe dentro de Compose): pide, y
     * la screen lo cumple. Mismo patrón que `ChatViewModel.clipboardRequest`.
     */
    private val _clipboardRequest = MutableStateFlow<String?>(null)
    val clipboardRequest: StateFlow<String?> = _clipboardRequest.asStateFlow()

    fun consumeClipboardRequest() {
        _clipboardRequest.value = null
    }

    /**
     * Carga inicial: posiciona el árbol en la raíz del workspace.
     *
     * NO hace un reset total del estado: preserva cualquier archivo ya abierto
     * (p. ej. cuando se abre el editor por un click en una referencia del chat,
     * `openFile` corre justo antes de que la pantalla monte y dispare `onOpen`).
     */
    fun onOpen() {
        viewModelScope.launch {
            val root = activeWorkspaceStore.current()
            if (root == null) {
                _state.update { it.copy(workspaceRoot = null) }
                return@launch
            }
            _state.update { it.copy(workspaceRoot = root, error = null) }
            loadChildren(root)
        }
    }

    private suspend fun loadChildren(path: String) {
        _state.update { it.copy(loadingPaths = it.loadingPaths + path, error = null) }
        when (val r = agent.listDirectory(path)) {
            is FsResult.Ok -> {
                val entries = parseEntries(r, path)
                _state.update {
                    it.copy(
                        childrenByPath = it.childrenByPath + (path to entries),
                        loadingPaths = it.loadingPaths - path
                    )
                }
            }
            is FsResult.Err -> _state.update {
                it.copy(loadingPaths = it.loadingPaths - path, error = r.message)
            }
        }
    }

    private fun parseEntries(result: FsResult.Ok, parentAbs: String): List<FsEntry> =
        result.payload["entries"]?.jsonArray.orEmptyList().map { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.content.orEmpty()
            val isDir = o["type"]?.jsonPrimitive?.content == "dir"
            FsEntry(name = name, isDir = isDir, path = "$parentAbs/$name")
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })

    /** Click sobre una fila: selecciona, y expande/colapsa (carpeta) o abre (archivo). */
    fun onEntryClick(entry: FsEntry) {
        _state.update { it.copy(selectedPath = entry.path) }
        if (entry.isDir) toggleExpand(entry) else openFile(entry.path)
    }

    fun selectEntry(path: String) {
        _state.update { it.copy(selectedPath = path) }
    }

    fun toggleExpand(entry: FsEntry) {
        if (!entry.isDir) return
        val path = entry.path
        val expanded = path in _state.value.expandedPaths
        if (expanded) {
            _state.update { it.copy(expandedPaths = it.expandedPaths - path) }
        } else {
            _state.update { it.copy(expandedPaths = it.expandedPaths + path) }
            if (path !in _state.value.childrenByPath) {
                viewModelScope.launch { loadChildren(path) }
            }
        }
    }

    fun refreshNode(path: String) = viewModelScope.launch { loadChildren(path) }

    /** Refresca la raíz y todas las carpetas actualmente expandidas. */
    fun refreshTree() = viewModelScope.launch {
        val root = _state.value.workspaceRoot ?: return@launch
        val toReload = listOf(root) + _state.value.expandedPaths
        toReload.forEach { loadChildren(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun openFile(path: String, line: Int? = null) = viewModelScope.launch {
        val abs = resolve(path) ?: return@launch
        completionJob?.cancel()
        _state.update {
            it.copy(
                loading = true,
                error = null,
                selectedPath = abs,
                cursorOffset = 0,
                suggestion = null,
                suggestionAnchor = -1,
                suggestionLoading = false
            )
        }
        val ext = abs.substringAfterLast('.', "").lowercase()

        if (ext in IMAGE_EXTENSIONS) {
            when (val r = agent.readFileBytes(abs)) {
                is FsResult.Ok -> {
                    val b64 = r.payload["content"]?.jsonPrimitive?.content.orEmpty()
                    val bytes = runCatching { Base64.decode(b64) }.getOrNull()
                    val bitmap = bytes?.let { decodeImage(it) }
                    _state.update {
                        it.copy(
                            openFilePath = abs,
                            openFileName = fileNameOf(abs),
                            content = "",
                            originalContent = "",
                            dirty = false,
                            loading = false,
                            imageBitmap = bitmap,
                            scrollToLine = null,
                            error = if (bitmap == null) "No se pudo decodificar la imagen" else null
                        )
                    }
                }
                is FsResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
            return@launch
        }

        when (val r = agent.readFileRaw(abs)) {
            is FsResult.Ok -> {
                val text = r.payload["content"]?.jsonPrimitive?.content.orEmpty()
                _state.update {
                    it.copy(
                        openFilePath = abs,
                        openFileName = fileNameOf(abs),
                        content = text,
                        originalContent = text,
                        dirty = false,
                        loading = false,
                        imageBitmap = null,
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

    // ── Edición y autocompletado ───────────────────────────────────────────────

    /**
     * Único punto de entrada de los cambios del campo de texto: llega tanto cuando se
     * edita el contenido como cuando solo se mueve el cursor o se selecciona texto (el
     * `TextFieldValue` notifica ambos por el mismo callback).
     *
     * Distinguir los dos casos importa: un movimiento de cursor no ensucia el archivo
     * ni debe disparar una sugerencia — solo descarta la que hubiera.
     */
    fun onContentChange(text: String, cursor: Int, selectionCollapsed: Boolean = true) {
        val prev = _state.value
        val textChanged = prev.content != text
        completionJob?.cancel()
        _state.update {
            it.copy(
                content = text,
                cursorOffset = cursor,
                dirty = if (textChanged) true else it.dirty,
                suggestion = null,
                suggestionAnchor = -1,
                suggestionLoading = false
            )
        }
        if (textChanged && selectionCollapsed && prev.codeCompletionEnabled) {
            scheduleCompletion(COMPLETION_DEBOUNCE_MS)
        }
    }

    /** Sugerencia a petición (Ctrl+Espacio): sin debounce, pero con el mismo flag maestro. */
    fun requestCompletion() {
        if (!_state.value.codeCompletionEnabled) return
        scheduleCompletion(0)
    }

    fun dismissSuggestion() {
        completionJob?.cancel()
        _state.update { it.copy(suggestion = null, suggestionAnchor = -1, suggestionLoading = false) }
    }

    private fun scheduleCompletion(delayMs: Long) {
        completionJob?.cancel()
        completionJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            runCompletion()
        }
    }

    /**
     * Pide al modelo del chat qué texto va en el cursor. Falla en silencio a propósito:
     * es una ayuda de fondo, no una acción que el usuario pidió y cuyo error espere ver
     * — un banner por cada timeout mientras escribe sería insoportable.
     */
    private suspend fun runCompletion() {
        val snapshot = _state.value
        val path = snapshot.openFilePath ?: return
        val name = snapshot.openFileName ?: return
        if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) return

        val cursor = snapshot.cursorOffset.coerceIn(0, snapshot.content.length)
        val prefix = snapshot.content.substring(0, cursor)
        val suffix = snapshot.content.substring(cursor)
        if (prefix.isBlank()) return

        val prefs = preferences.current()
        if (!prefs.codeCompletionEnabled) return
        // El autocompletado es la tarea que más se beneficia de un modelo chico: se pide
        // en cada pausa al teclear. Sin fallback al principal: "sin sugerencia" es la
        // respuesta normal, y reintentar con el grande duplicaría cada petición.
        val cfg = prefs.auxiliaryConnection ?: prefs.connection
        if (!cfg.isValid()) return

        _state.update { it.copy(suggestionLoading = true) }
        val text = runCatching {
            modelRepository.completeCode(
                baseUrl = cfg.baseUrl(),
                model = cfg.model,
                prefix = prefix,
                suffix = suffix,
                fileName = name,
                apiKeyOverride = cfg.apiKey.takeIf { it.isNotBlank() }
            )
        }.getOrNull()

        // Aunque cada tecla cancela el job, la respuesta puede llegar en el hueco entre
        // el último `await` y la cancelación: se compara contra el estado actual y se
        // descarta si el editor ya no está donde se pidió, en vez de pintar la sugerencia
        // en un sitio que no le corresponde.
        val now = _state.value
        val stale = now.openFilePath != path ||
            now.content != snapshot.content ||
            now.cursorOffset != cursor
        _state.update {
            if (stale) {
                it.copy(suggestionLoading = false)
            } else {
                it.copy(
                    suggestionLoading = false,
                    suggestion = text?.takeIf { s -> s.isNotEmpty() },
                    suggestionAnchor = cursor
                )
            }
        }
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

    // ── Crear archivo / carpeta ────────────────────────────────────────────────

    fun requestNewEntry(parentDir: String, isDir: Boolean) {
        _state.update { it.copy(newEntryPrompt = NewEntryPrompt(parentDir, isDir)) }
    }

    fun cancelNewEntry() {
        _state.update { it.copy(newEntryPrompt = null) }
    }

    fun confirmNewEntry(name: String) = viewModelScope.launch {
        val prompt = _state.value.newEntryPrompt ?: return@launch
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        val abs = resolve("${prompt.parentDir}/$trimmed") ?: return@launch
        val result = if (prompt.isDir) {
            agent.createDirectory(abs)
        } else {
            agent.createFile(abs, "", overwrite = false)
        }
        when (result) {
            is FsResult.Ok -> {
                _state.update { it.copy(newEntryPrompt = null) }
                expandAndRefresh(prompt.parentDir)
                if (!prompt.isDir) openFile(abs)
            }
            is FsResult.Err -> _state.update {
                it.copy(newEntryPrompt = prompt.copy(error = result.message))
            }
        }
    }

    private suspend fun expandAndRefresh(path: String) {
        _state.update { it.copy(expandedPaths = it.expandedPaths + path) }
        loadChildren(path)
    }

    // ── Renombrar ───────────────────────────────────────────────────────────────

    fun requestRename(entry: FsEntry) {
        _state.update { it.copy(renamePrompt = RenamePrompt(entry.path, entry.isDir, entry.name)) }
    }

    fun cancelRename() {
        _state.update { it.copy(renamePrompt = null) }
    }

    fun confirmRename(newName: String) = viewModelScope.launch {
        val prompt = _state.value.renamePrompt ?: return@launch
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == prompt.currentName) {
            _state.update { it.copy(renamePrompt = null) }
            return@launch
        }
        val parent = parentOf(prompt.path)
        val toAbs = resolve("$parent/$trimmed") ?: return@launch
        when (val r = agent.renamePath(prompt.path, toAbs)) {
            is FsResult.Ok -> {
                _state.update { it.copy(renamePrompt = null) }
                applyMoveEffects(fromPath = prompt.path, toAbs = toAbs, isDir = prompt.isDir)
                loadChildren(parent)
            }
            is FsResult.Err -> _state.update { it.copy(renamePrompt = prompt.copy(error = r.message)) }
        }
    }

    // ── Mover (drag & drop) ──────────────────────────────────────────────────────

    /**
     * Mueve [entry] a la carpeta [targetDir] (arrastrar y soltar en el árbol).
     * Reusa [FilesystemAgent.renamePath] (`Files.move`), que ya soporta mover entre
     * directorios distintos, no solo renombrar en el mismo. No-op si [targetDir] es
     * la carpeta que ya contiene a [entry]; rechaza mover una carpeta dentro de sí
     * misma o de uno de sus propios descendientes.
     */
    fun moveEntry(entry: FsEntry, targetDir: String) = viewModelScope.launch {
        val sourceParent = parentOf(entry.path)
        if (targetDir == sourceParent) return@launch
        if (entry.isDir && (targetDir == entry.path || targetDir.startsWith(entry.path + "/"))) {
            _state.update { it.copy(error = "No se puede mover una carpeta dentro de sí misma.") }
            return@launch
        }
        val toAbs = resolve("$targetDir/${entry.name}") ?: return@launch
        when (val r = agent.renamePath(entry.path, toAbs)) {
            is FsResult.Ok -> {
                applyMoveEffects(fromPath = entry.path, toAbs = toAbs, isDir = entry.isDir)
                loadChildren(sourceParent)
                loadChildren(targetDir)
            }
            is FsResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    /**
     * Actualiza el estado tras un `renamePath` exitoso (compartido por renombrar y
     * mover): descarta el subárbol cacheado bajo el path viejo si era una carpeta
     * (se recarga perezosamente al volver a expandir), y si el path movido es -o
     * contiene- al archivo abierto o a la selección actual, los reapunta al nuevo path.
     */
    private fun applyMoveEffects(fromPath: String, toAbs: String, isDir: Boolean) {
        if (isDir) {
            val prefix = fromPath + "/"
            _state.update { st ->
                st.copy(
                    childrenByPath = st.childrenByPath.filterKeys { it != fromPath && !it.startsWith(prefix) },
                    expandedPaths = st.expandedPaths.filterNot { it == fromPath || it.startsWith(prefix) }.toSet()
                )
            }
        }
        val open = _state.value.openFilePath
        if (open != null) {
            if (open == fromPath) {
                _state.update { it.copy(openFilePath = toAbs, openFileName = fileNameOf(toAbs)) }
            } else if (isDir && open.startsWith(fromPath + "/")) {
                _state.update { it.copy(openFilePath = toAbs + open.removePrefix(fromPath)) }
            }
        }
        if (_state.value.selectedPath == fromPath) {
            _state.update { it.copy(selectedPath = toAbs) }
        }
    }

    // ── Eliminar ────────────────────────────────────────────────────────────────

    fun requestDelete(entry: FsEntry) {
        _state.update { it.copy(deleteConfirm = DeleteConfirm(entry.path, entry.isDir, entry.name)) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteConfirm = null) }
    }

    fun confirmDelete() = viewModelScope.launch {
        val prompt = _state.value.deleteConfirm ?: return@launch
        when (val r = agent.deletePath(prompt.path, recursive = true)) {
            is FsResult.Ok -> {
                val parent = parentOf(prompt.path)
                val prefix = prompt.path + "/"
                _state.update { st ->
                    st.copy(
                        deleteConfirm = null,
                        childrenByPath = (st.childrenByPath - prompt.path).filterKeys { !it.startsWith(prefix) },
                        expandedPaths = st.expandedPaths.filterNot { it == prompt.path || it.startsWith(prefix) }.toSet(),
                        selectedPath = if (st.selectedPath == prompt.path) null else st.selectedPath
                    )
                }
                val open = _state.value.openFilePath
                if (open != null && (open == prompt.path || (prompt.isDir && open.startsWith(prefix)))) {
                    closeFile()
                }
                loadChildren(parent)
            }
            is FsResult.Err -> _state.update { it.copy(deleteConfirm = prompt.copy(error = r.message)) }
        }
    }

    // ── Duplicar / copiar ruta / revelar ───────────────────────────────────────

    fun duplicateEntry(entry: FsEntry) = viewModelScope.launch {
        val parent = parentOf(entry.path)
        val siblings = _state.value.childrenByPath[parent].orEmpty().map { it.name }.toSet()
        val dotIdx = if (entry.isDir) -1 else entry.name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val base = if (dotIdx > 0) entry.name.substring(0, dotIdx) else entry.name
        val ext = if (dotIdx > 0) entry.name.substring(dotIdx) else ""
        var candidate = "$base (copia)$ext"
        var n = 2
        while (candidate in siblings) {
            candidate = "$base (copia $n)$ext"
            n++
        }
        val toAbs = resolve("$parent/$candidate") ?: return@launch
        when (val r = agent.copyPath(entry.path, toAbs)) {
            is FsResult.Ok -> loadChildren(parent)
            is FsResult.Err -> _state.update { it.copy(error = r.message) }
        }
    }

    /** Copia la ruta relativa al workspace al portapapeles (vía [clipboardRequest]). */
    fun copyPathToClipboard(entry: FsEntry) {
        val root = _state.value.workspaceRoot
        val display = if (root != null && entry.path.startsWith(root)) {
            entry.path.removePrefix(root).trimStart('/', '\\')
        } else {
            entry.path
        }
        _clipboardRequest.value = display
    }

    fun revealInFileManager(entry: FsEntry) {
        com.localchatbot.core.platform.revealInFileManager(entry.path)
    }

    // ── Cerrar / errores ────────────────────────────────────────────────────────

    fun closeFile() {
        completionJob?.cancel()
        _state.update {
            it.copy(
                openFilePath = null,
                openFileName = null,
                content = "",
                originalContent = "",
                dirty = false,
                imageBitmap = null,
                cursorOffset = 0,
                suggestion = null,
                suggestionAnchor = -1,
                suggestionLoading = false
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Resuelve siempre contra el workspace, sin permitir salir de él. */
    private suspend fun resolve(input: String): String? {
        val root = activeWorkspaceStore.current()
        return when (val r = agent.resolveSafePath(workspace = root, input = input, allowOutside = false)) {
            is SafePathResult.Ok -> r.absPath
            is SafePathResult.Err -> {
                _state.update { it.copy(error = r.message) }
                null
            }
        }
    }

    private fun fileNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun parentOf(path: String): String {
        val idx = path.indexOfLast { it == '/' || it == '\\' }
        return if (idx <= 0) path else path.substring(0, idx)
    }

    private companion object {
        /**
         * Pausa al teclear antes de pedir una sugerencia. Un modelo local tarda ya de por
         * sí bastante en contestar, así que el debounce no busca ahorrar latencia sino
         * evitar disparar (y cancelar) una petición por cada tecla mientras se escribe.
         */
        const val COMPLETION_DEBOUNCE_MS = 500L
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmptyList() = this ?: emptyList()
