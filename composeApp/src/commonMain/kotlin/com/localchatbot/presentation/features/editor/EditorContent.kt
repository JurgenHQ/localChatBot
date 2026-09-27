package com.localchatbot.presentation.features.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.util.ContextMenuEntry
import com.localchatbot.presentation.components.util.WithContextMenu
import com.localchatbot.presentation.preview.PreviewSurface
import com.mikepenz.markdown.m3.Markdown
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Carpeta contenedora de [path] (nuestros paths se construyen siempre con "/" como separador). */
private fun parentDirOf(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) path else path.substring(0, idx)
}

@Composable
fun EditorContent(
    state: EditorUiState,
    onClose: () -> Unit,
    onEntryClick: (FsEntry) -> Unit,
    onRequestNewEntry: (parentDir: String, isDir: Boolean) -> Unit,
    onConfirmNewEntry: (String) -> Unit,
    onCancelNewEntry: () -> Unit,
    onRequestRename: (FsEntry) -> Unit,
    onConfirmRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: (FsEntry) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDuplicate: (FsEntry) -> Unit,
    onCopyPath: (FsEntry) -> Unit,
    onReveal: (FsEntry) -> Unit,
    onRefreshTree: () -> Unit,
    onRefreshNode: (String) -> Unit,
    onMoveEntry: (FsEntry, String) -> Unit,
    onContentChange: (text: String, cursor: Int, selectionCollapsed: Boolean) -> Unit,
    onRequestCompletion: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    onSave: () -> Unit,
    onRequestSave: () -> Unit = onSave,
    onConfirmSave: () -> Unit = onSave,
    onCancelSave: () -> Unit = {},
    onCloseFile: () -> Unit,
    onClearError: () -> Unit,
    onClearScrollToLine: () -> Unit = {},
    onToggleSearch: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onNextMatch: () -> Unit = {},
    onPrevMatch: () -> Unit = {},
    onTogglePreview: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Volver",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "Editor",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
        }

        if (state.workspaceRoot == null) {
            Text(
                "Configura un workspace en la pestaña Agente para usar el editor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        state.error?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onClearError)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            FileExplorerPane(
                state = state,
                workspaceRoot = state.workspaceRoot,
                onEntryClick = onEntryClick,
                onRequestNewEntry = onRequestNewEntry,
                onRequestRename = onRequestRename,
                onRequestDelete = onRequestDelete,
                onDuplicate = onDuplicate,
                onCopyPath = onCopyPath,
                onReveal = onReveal,
                onRefreshTree = onRefreshTree,
                onRefreshNode = onRefreshNode,
                onMoveEntry = onMoveEntry,
                modifier = Modifier.width(300.dp).fillMaxHeight()
            )
            EditorPane(
                state = state,
                onContentChange = onContentChange,
                onRequestCompletion = onRequestCompletion,
                onDismissSuggestion = onDismissSuggestion,
                onRequestSave = onRequestSave,
                onCloseFile = onCloseFile,
                onClearScrollToLine = onClearScrollToLine,
                onToggleSearch = onToggleSearch,
                onSearchQueryChange = onSearchQueryChange,
                onNextMatch = onNextMatch,
                onPrevMatch = onPrevMatch,
                onTogglePreview = onTogglePreview,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }

    // Diff-preview dialog
    if (state.pendingDiff != null) {
        SaveDiffDialog(
            fileName = state.openFileName ?: "archivo",
            diff = state.pendingDiff,
            onConfirm = onConfirmSave,
            onDismiss = onCancelSave
        )
    }
    state.newEntryPrompt?.let { prompt ->
        NewEntryDialog(prompt = prompt, onConfirm = onConfirmNewEntry, onDismiss = onCancelNewEntry)
    }
    state.renamePrompt?.let { prompt ->
        RenameEntryDialog(prompt = prompt, onConfirm = onConfirmRename, onDismiss = onCancelRename)
    }
    state.deleteConfirm?.let { prompt ->
        DeleteEntryDialog(prompt = prompt, onConfirm = onConfirmDelete, onDismiss = onCancelDelete)
    }
}

// ── File explorer (árbol expandible) ────────────────────────────────────────────

@Composable
private fun FileExplorerPane(
    state: EditorUiState,
    workspaceRoot: String,
    onEntryClick: (FsEntry) -> Unit,
    onRequestNewEntry: (parentDir: String, isDir: Boolean) -> Unit,
    onRequestRename: (FsEntry) -> Unit,
    onRequestDelete: (FsEntry) -> Unit,
    onDuplicate: (FsEntry) -> Unit,
    onCopyPath: (FsEntry) -> Unit,
    onReveal: (FsEntry) -> Unit,
    onRefreshTree: () -> Unit,
    onRefreshNode: (String) -> Unit,
    onMoveEntry: (FsEntry, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    // Drag & drop: cada fila registra su posición en pantalla al componerse; durante
    // un arrastre se compara la posición del puntero contra esos rects para saber
    // qué fila está debajo (hit-test manual, no hay reorder API nativa en LazyColumn).
    val rowCoordinates = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var draggedPath by remember { mutableStateOf<String?>(null) }
    var hoverTargetPath by remember { mutableStateOf<String?>(null) }
    val entryByPath = remember(state.visibleRows) { state.visibleRows.associate { it.entry.path to it.entry } }

    fun resolveHoverTarget(rootPosition: Offset): String? {
        for ((path, coords) in rowCoordinates) {
            if (!coords.isAttached) continue
            val bounds = coords.boundsInRoot()
            if (rootPosition.y in bounds.top..bounds.bottom) return path
        }
        return null
    }

    fun handleDragEnd(dragged: FsEntry) {
        val hoverEntry = hoverTargetPath?.let { entryByPath[it] }
        draggedPath = null
        hoverTargetPath = null
        val targetDir = when {
            hoverEntry == null -> workspaceRoot
            hoverEntry.isDir -> hoverEntry.path
            else -> parentDirOf(hoverEntry.path)
        }
        onMoveEntry(dragged, targetDir)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val entry = state.selectedEntry
                if (entry != null && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Delete, Key.Backspace -> {
                            onRequestDelete(entry)
                            true
                        }
                        Key.F2 -> {
                            onRequestRename(entry)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Workspace",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { onRequestNewEntry(workspaceRoot, false) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Nuevo archivo",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { onRequestNewEntry(workspaceRoot, true) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = "Nueva carpeta",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onRefreshTree, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Refrescar árbol",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.visibleRows, key = { it.entry.path }) { row ->
                TreeEntryRow(
                    row = row,
                    selected = row.entry.path == state.selectedPath,
                    openFilePath = state.openFilePath,
                    loading = row.entry.path in state.loadingPaths,
                    expanded = row.entry.path in state.expandedPaths,
                    isDragging = row.entry.path == draggedPath,
                    isDropTarget = row.entry.isDir && row.entry.path == hoverTargetPath && draggedPath != null && draggedPath != row.entry.path,
                    onClick = {
                        focusRequester.requestFocus()
                        onEntryClick(row.entry)
                    },
                    onRequestNewFile = { onRequestNewEntry(row.entry.path, false) },
                    onRequestNewFolder = { onRequestNewEntry(row.entry.path, true) },
                    onRequestRename = { onRequestRename(row.entry) },
                    onRequestDelete = { onRequestDelete(row.entry) },
                    onDuplicate = { onDuplicate(row.entry) },
                    onCopyPath = { onCopyPath(row.entry) },
                    onReveal = { onReveal(row.entry) },
                    onRefresh = { onRefreshNode(row.entry.path) },
                    onRegisterCoordinates = { coords -> rowCoordinates[row.entry.path] = coords },
                    onDragStart = { draggedPath = row.entry.path; hoverTargetPath = row.entry.path },
                    onDragMove = { rootPos -> hoverTargetPath = resolveHoverTarget(rootPos) },
                    onDragEnd = { handleDragEnd(row.entry) },
                    onDragCancel = { draggedPath = null; hoverTargetPath = null }
                )
            }
            if (state.visibleRows.isEmpty() && workspaceRoot !in state.loadingPaths) {
                item {
                    Text(
                        "Workspace vacío.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TreeEntryRow(
    row: TreeRow,
    selected: Boolean,
    openFilePath: String?,
    loading: Boolean,
    expanded: Boolean,
    isDragging: Boolean,
    isDropTarget: Boolean,
    onClick: () -> Unit,
    onRequestNewFile: () -> Unit,
    onRequestNewFolder: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyPath: () -> Unit,
    onReveal: () -> Unit,
    onRefresh: () -> Unit,
    onRegisterCoordinates: (LayoutCoordinates) -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (rootPosition: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val entry = row.entry
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    val highlighted = selected || entry.path == openFilePath
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    rowCoords = it
                    onRegisterCoordinates(it)
                }
                .then(
                    if (PlatformCapabilities.isDesktop) {
                        Modifier
                            .clickable(onClick = onClick)
                            .pointerInput(entry.path) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { onDragStart() },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val coords = rowCoords ?: return@detectDragGesturesAfterLongPress
                                        onDragMove(coords.localToRoot(change.position))
                                    },
                                    onDragEnd = onDragEnd,
                                    onDragCancel = onDragCancel
                                )
                            }
                    } else {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                            onLongClick = { menuOpen = true }
                        )
                    }
                )
                .background(
                    when {
                        isDropTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                )
                .then(
                    if (isDropTarget) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm))
                    } else {
                        Modifier
                    }
                )
                .then(if (isDragging) Modifier.alpha(0.4f) else Modifier)
                .padding(
                    start = Spacing.md + (row.depth * 16).dp,
                    end = Spacing.sm,
                    top = Spacing.sm,
                    bottom = Spacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (entry.isDir) {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Spacer(Modifier.size(16.dp))
            }
            Icon(
                when {
                    entry.isDir -> Icons.Outlined.Folder
                    ext in IMAGE_EXTENSIONS -> Icons.Outlined.ImageIcon
                    else -> Icons.Outlined.Description
                },
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
        }
    }

    val menuItems: List<ContextMenuEntry> = buildList {
        if (entry.isDir) {
            add(ContextMenuEntry("Nuevo archivo aquí", onRequestNewFile))
            add(ContextMenuEntry("Nueva carpeta aquí", onRequestNewFolder))
        }
        add(ContextMenuEntry("Renombrar", onRequestRename))
        add(ContextMenuEntry("Duplicar", onDuplicate))
        add(ContextMenuEntry("Copiar ruta", onCopyPath))
        add(ContextMenuEntry("Revelar en el explorador", onReveal))
        if (entry.isDir) {
            add(ContextMenuEntry("Refrescar", onRefresh))
        }
        add(ContextMenuEntry("Eliminar", onRequestDelete))
    }

    if (PlatformCapabilities.isDesktop) {
        WithContextMenu(items = { menuItems }) {
            rowContent()
        }
    } else {
        Box {
            rowContent()
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuItems.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            menuOpen = false
                            item.onClick()
                        }
                    )
                }
            }
        }
    }
}

// ── Diálogos de gestión de archivos ─────────────────────────────────────────────

@Composable
private fun NewEntryDialog(prompt: NewEntryPrompt, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(prompt.parentDir, prompt.isDir) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prompt.isDir) "Nueva carpeta" else "Nuevo archivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(if (prompt.isDir) "carpeta" else "archivo.txt") },
                    singleLine = true
                )
                prompt.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Crear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun RenameEntryDialog(prompt: RenamePrompt, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(prompt.path) { mutableStateOf(prompt.currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renombrar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true
                )
                prompt.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun DeleteEntryDialog(prompt: DeleteConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prompt.isDir) "Eliminar carpeta" else "Eliminar archivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    if (prompt.isDir) {
                        "Se eliminará \"${prompt.name}\" y todo su contenido. Esta acción no se puede deshacer."
                    } else {
                        "Se eliminará \"${prompt.name}\". Esta acción no se puede deshacer."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                prompt.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// ── Editor pane ───────────────────────────────────────────────────────────────

@Composable
private fun EditorPane(
    state: EditorUiState,
    onContentChange: (text: String, cursor: Int, selectionCollapsed: Boolean) -> Unit,
    onRequestCompletion: () -> Unit,
    onDismissSuggestion: () -> Unit,
    onRequestSave: () -> Unit,
    onCloseFile: () -> Unit,
    onClearScrollToLine: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onNextMatch: () -> Unit,
    onPrevMatch: () -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.openFilePath == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.md))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Selecciona un archivo para editarlo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        val ext = state.openFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        val isMarkdown = ext == "md" || ext == "markdown"
        val isImage = ext in IMAGE_EXTENSIONS

        // Header: nombre, botones de acción, cerrar
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                (state.openFileName ?: "") + if (state.dirty) " •" else "",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            // Preview toggle — solo para archivos Markdown
            if (isMarkdown) {
                IconButton(onClick = onTogglePreview) {
                    Icon(
                        if (state.previewMode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.previewMode) "Editar" else "Vista previa",
                        tint = if (state.previewMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // Búsqueda — oculta en preview y en imágenes
            if (!state.previewMode && !isImage) {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = "Buscar en archivo",
                        tint = if (state.searchVisible) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onCloseFile) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Cerrar archivo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── Preview de imagen (solo lectura) ─────────────────────────────────
        if (isImage) {
            val bitmap = state.imageBitmap
            if (bitmap != null) {
                ImagePreviewPane(
                    bitmap = bitmap,
                    fileName = state.openFileName ?: "imagen",
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.loading) "Cargando imagen…" else "No se pudo previsualizar la imagen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        // Barra de búsqueda
        if (state.searchVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                AppTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = "Buscar…",
                    modifier = Modifier.weight(1f)
                )
                if (state.searchMatches.isNotEmpty()) {
                    Text(
                        "${state.currentMatchIndex + 1}/${state.searchMatches.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPrevMatch, enabled = state.searchMatches.isNotEmpty()) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Anterior", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onNextMatch, enabled = state.searchMatches.isNotEmpty()) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Siguiente", modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── Preview mode (Markdown) ───────────────────────────────────────────
        if (state.previewMode) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md)
            ) {
                Markdown(
                    content = state.content,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PrimaryButton(
                text = "Guardar",
                onClick = onRequestSave,
                enabled = state.dirty && !state.loading
            )
            return@Column
        }

        // ── Edit mode ─────────────────────────────────────────────────────────
        val scrollState = rememberScrollState()
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

        // El campo es dueño del texto Y del cursor mientras hay un archivo abierto; el VM
        // lo refleja vía onContentChange. Solo se re-siembra cuando el VM carga contenido
        // distinto del disco (abrir/recargar): al guardar, `originalContent` cambia pero el
        // texto no, y re-sembrar ahí devolvería el cursor al principio en cada guardado.
        var fieldValue by remember { mutableStateOf(TextFieldValue(state.content)) }
        LaunchedEffect(state.openFilePath, state.originalContent) {
            if (fieldValue.text != state.content) {
                fieldValue = TextFieldValue(state.content, TextRange(0))
            }
        }

        // La sugerencia solo se pinta si sigue anclada donde está el cursor ahora mismo.
        val ghost = state.suggestion
            ?.takeIf { fieldValue.selection.collapsed && state.suggestionAnchor == fieldValue.selection.start }

        fun acceptSuggestion(text: String) {
            val at = fieldValue.selection.start
            val updated = fieldValue.text.substring(0, at) + text + fieldValue.text.substring(at)
            val cursor = at + text.length
            fieldValue = TextFieldValue(updated, TextRange(cursor))
            onContentChange(updated, cursor, true)
        }

        // Scroll a línea pedida
        val targetLine = state.scrollToLine
        LaunchedEffect(targetLine, textLayout) {
            val layout = textLayout ?: return@LaunchedEffect
            val line = targetLine ?: return@LaunchedEffect
            val top = layout.getLineTop((line - 1).coerceIn(0, layout.lineCount - 1)).toInt()
            scrollState.scrollTo(top)
            onClearScrollToLine()
        }

        // Scroll a coincidencia activa
        val matchIdx = state.currentMatchIndex
        val matches = state.searchMatches
        LaunchedEffect(matchIdx, textLayout) {
            val layout = textLayout ?: return@LaunchedEffect
            if (matchIdx < 0 || matchIdx >= matches.size) return@LaunchedEffect
            val charIdx = matches[matchIdx].coerceAtMost(layout.layoutInput.text.length.coerceAtLeast(0))
            val line = layout.getLineForOffset(charIdx)
            scrollState.scrollTo(layout.getLineTop(line).toInt())
        }

        // Colores de sintaxis desde el tema
        val kwColor = MaterialTheme.colorScheme.primary
        val strColor = MaterialTheme.colorScheme.tertiary
        val commentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
        val numColor = MaterialTheme.colorScheme.secondary
        val annColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
        val matchColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        val activeMatchColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

        // Pre-computa spans de sintaxis solo cuando cambia el contenido/extensión
        val syntaxSpans = remember(state.content, ext) {
            SyntaxHighlighter.highlight(state.content, ext)
        }

        // Transformación combinada: sintaxis + búsqueda + sugerencia fantasma
        val ghostColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        val transformation = rememberCombinedTransformation(
            syntaxSpans = syntaxSpans,
            searchMatches = matches,
            currentMatchIdx = matchIdx,
            queryLength = state.searchQuery.length,
            ghostText = ghost,
            ghostAnchor = state.suggestionAnchor,
            ghostColor = ghostColor,
            kwColor = kwColor,
            strColor = strColor,
            commentColor = commentColor,
            numColor = numColor,
            annColor = annColor,
            matchColor = matchColor,
            activeMatchColor = activeMatchColor
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    onContentChange(it.text, it.selection.start, it.selection.collapsed)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(Spacing.md)
                    // Preview: Tab/Esc tienen que llegar antes que el manejo por
                    // defecto del campo (Tab movería el foco). Esc solo se consume
                    // si hay sugerencia visible; si no, sigue hasta el Esc global
                    // de MainScaffold, que cierra el editor.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Tab && ghost != null -> {
                                acceptSuggestion(ghost)
                                true
                            }
                            event.key == Key.Escape && ghost != null -> {
                                onDismissSuggestion()
                                true
                            }
                            event.key == Key.Spacebar && event.isCtrlPressed -> {
                                onRequestCompletion()
                                true
                            }
                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
                onTextLayout = { textLayout = it }
            )
        }

        if (state.codeCompletionEnabled) {
            Text(
                when {
                    ghost != null -> "Tab para aceptar la sugerencia · Esc para descartarla"
                    state.suggestionLoading -> "Pensando una sugerencia…"
                    else -> "Autocompletado activo · Ctrl+Espacio para pedir una sugerencia"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PrimaryButton(
            text = "Guardar",
            onClick = onRequestSave,
            enabled = state.dirty && !state.loading
        )
    }
}

// ── Preview de imagen (zoom básico con pinch/scroll + doble-tap para resetear) ──

@Composable
private fun ImagePreviewPane(
    bitmap: ImageBitmap,
    fileName: String,
    modifier: Modifier = Modifier
) {
    var scale by remember(fileName) { mutableStateOf(1f) }
    var offset by remember(fileName) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .clipToBounds()
            .pointerInput(fileName) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    offset = if (newScale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(fileName) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.md)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

// ── Combined VisualTransformation ─────────────────────────────────────────────

@Composable
private fun rememberCombinedTransformation(
    syntaxSpans: List<SyntaxSpan>,
    searchMatches: List<Int>,
    currentMatchIdx: Int,
    queryLength: Int,
    ghostText: String?,
    ghostAnchor: Int,
    ghostColor: Color,
    kwColor: Color,
    strColor: Color,
    commentColor: Color,
    numColor: Color,
    annColor: Color,
    matchColor: Color,
    activeMatchColor: Color
): VisualTransformation = remember(
    syntaxSpans, searchMatches, currentMatchIdx, queryLength, ghostText, ghostAnchor, ghostColor,
    kwColor, strColor, commentColor, numColor, annColor, matchColor, activeMatchColor
) {
    val hasSyntax = syntaxSpans.isNotEmpty()
    val hasSearch = queryLength > 0 && searchMatches.isNotEmpty()
    val ghost = ghostText?.takeIf { it.isNotEmpty() && ghostAnchor >= 0 }
    if (!hasSyntax && !hasSearch && ghost == null) return@remember VisualTransformation.None

    VisualTransformation { text ->
        val anchor = ghost?.let { ghostAnchor.coerceIn(0, text.length) } ?: -1
        val ghostLen = ghost?.length ?: 0

        // El texto fantasma NO está en el contenido real: se inserta solo para pintarlo,
        // así que todos los offsets posteriores al cursor se desplazan y hay que mapearlos
        // en ambos sentidos (sin esto el cursor y la selección apuntarían al carácter
        // equivocado en cuanto apareciera una sugerencia).
        fun shift(offset: Int): Int = if (anchor < 0 || offset <= anchor) offset else offset + ghostLen

        val displayed: AnnotatedString = if (ghost == null) {
            text
        } else {
            buildAnnotatedString {
                append(text.subSequence(0, anchor))
                append(ghost)
                append(text.subSequence(anchor, text.length))
            }
        }
        val builder = AnnotatedString.Builder(displayed)
        val len = displayed.length

        // Sintaxis (color de texto)
        if (hasSyntax) {
            syntaxSpans.forEach { span ->
                val s = shift(span.start).coerceAtMost(len)
                val e = shift(span.end).coerceAtMost(len)
                if (s >= e) return@forEach
                val color = when (span.type) {
                    SyntaxType.Keyword -> kwColor
                    SyntaxType.StringLiteral -> strColor
                    SyntaxType.Comment -> commentColor
                    SyntaxType.Number -> numColor
                    SyntaxType.Annotation -> annColor
                    SyntaxType.Tag -> kwColor
                    SyntaxType.Key -> kwColor.copy(alpha = 0.85f)
                }
                builder.addStyle(SpanStyle(color = color), s, e)
            }
        }

        // Búsqueda (fondo — no interfiere con el color de texto)
        if (hasSearch) {
            searchMatches.forEachIndexed { i, start ->
                val s = shift(start).coerceAtMost(len)
                val e = shift(start + queryLength).coerceAtMost(len)
                if (s < e) {
                    builder.addStyle(
                        SpanStyle(background = if (i == currentMatchIdx) activeMatchColor else matchColor),
                        s, e
                    )
                }
            }
        }

        // Se aplica al final a propósito: un span de sintaxis que cruce el cursor abarca
        // también el hueco del fantasma, y el último estilo añadido es el que manda.
        if (ghost != null) {
            builder.addStyle(
                SpanStyle(color = ghostColor, fontStyle = FontStyle.Italic),
                anchor,
                (anchor + ghostLen).coerceAtMost(len)
            )
        }

        val mapping = if (ghost == null) {
            OffsetMapping.Identity
        } else {
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = shift(offset)
                override fun transformedToOriginal(offset: Int): Int = when {
                    offset <= anchor -> offset
                    offset <= anchor + ghostLen -> anchor
                    else -> offset - ghostLen
                }
            }
        }

        TransformedText(builder.toAnnotatedString(), mapping)
    }
}

// ── Save diff dialog ──────────────────────────────────────────────────────────

@Composable
private fun SaveDiffDialog(
    fileName: String,
    diff: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val addColor = Color(0xFF1B5E20)
    val removeColor = Color(0xFFB71C1C)
    val addBg = Color(0xFFE8F5E9)
    val removeBg = Color(0xFFFFEBEE)

    val annotated: AnnotatedString = remember(diff) {
        buildAnnotatedString {
            diff.lines().forEach { line ->
                when {
                    line.startsWith("+ ") || line == "+" ->
                        withStyle(SpanStyle(color = addColor, background = addBg)) { append(line) }
                    line.startsWith("- ") || line == "-" ->
                        withStyle(SpanStyle(color = removeColor, background = removeBg)) { append(line) }
                    else -> append(line)
                }
                append('\n')
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Guardar $fileName",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(Spacing.md)
            ) {
                Text(
                    text = annotated,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Guardar", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview
@Composable
private fun EditorContentPreview() {
    PreviewSurface {
        EditorContent(
            state = EditorUiState(
                workspaceRoot = "/home/user/proj",
                childrenByPath = mapOf(
                    "/home/user/proj" to listOf(
                        FsEntry("src", true, "/home/user/proj/src"),
                        FsEntry("README.md", false, "/home/user/proj/README.md")
                    )
                ),
                openFilePath = "/home/user/proj/README.md",
                openFileName = "README.md",
                content = "# Hello\n\nEdit me.",
                dirty = true
            ),
            onClose = {},
            onEntryClick = {},
            onRequestNewEntry = { _, _ -> },
            onConfirmNewEntry = {},
            onCancelNewEntry = {},
            onRequestRename = {},
            onConfirmRename = {},
            onCancelRename = {},
            onRequestDelete = {},
            onConfirmDelete = {},
            onCancelDelete = {},
            onDuplicate = {},
            onCopyPath = {},
            onReveal = {},
            onRefreshTree = {},
            onRefreshNode = {},
            onMoveEntry = { _, _ -> },
            onContentChange = { _, _, _ -> },
            onSave = {},
            onCloseFile = {},
            onClearError = {}
        )
    }
}
