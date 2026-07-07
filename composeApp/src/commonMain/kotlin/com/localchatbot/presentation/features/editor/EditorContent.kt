package com.localchatbot.presentation.features.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.preview.PreviewSurface
import com.mikepenz.markdown.m3.Markdown
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun EditorContent(
    state: EditorUiState,
    onClose: () -> Unit,
    onNavigate: (String) -> Unit,
    onGoUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onRequestSave: () -> Unit = onSave,
    onConfirmSave: () -> Unit = onSave,
    onCancelSave: () -> Unit = {},
    onCreateFile: (String) -> Unit,
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
                onNavigate = onNavigate,
                onGoUp = onGoUp,
                onOpenFile = onOpenFile,
                onCreateFile = onCreateFile,
                modifier = Modifier.width(280.dp).fillMaxHeight()
            )
            EditorPane(
                state = state,
                onContentChange = onContentChange,
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
}

// ── File explorer ─────────────────────────────────────────────────────────────

@Composable
private fun FileExplorerPane(
    state: EditorUiState,
    onNavigate: (String) -> Unit,
    onGoUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewFile by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                state.relativeDir,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showNewFile = !showNewFile }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Nuevo archivo",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (showNewFile) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                AppTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    placeholder = "nombre.txt",
                    monospace = true
                )
                PrimaryButton(
                    text = "Crear",
                    onClick = {
                        onCreateFile(newFileName)
                        newFileName = ""
                        showNewFile = false
                    },
                    enabled = newFileName.isNotBlank()
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.canGoUp) {
                item {
                    EntryRow(
                        label = "..",
                        isDir = true,
                        onClick = onGoUp,
                        iconUp = true
                    )
                }
            }
            items(state.entries, key = { it.path }) { entry ->
                EntryRow(
                    label = entry.name,
                    isDir = entry.isDir,
                    selected = entry.path == state.openFilePath,
                    onClick = {
                        if (entry.isDir) onNavigate(entry.path) else onOpenFile(entry.path)
                    }
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    label: String,
    isDir: Boolean,
    selected: Boolean = false,
    iconUp: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            when {
                iconUp -> Icons.Outlined.KeyboardArrowUp
                isDir -> Icons.Outlined.Folder
                else -> Icons.Outlined.Description
            },
            contentDescription = null,
            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ── Editor pane ───────────────────────────────────────────────────────────────

@Composable
private fun EditorPane(
    state: EditorUiState,
    onContentChange: (String) -> Unit,
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

        // Header: nombre, botones de acción, cerrar
        val ext = state.openFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        val isMarkdown = ext == "md" || ext == "markdown"
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
            // Búsqueda — oculta en preview
            if (!state.previewMode) {
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

        // Transformación combinada: sintaxis + búsqueda
        val transformation = rememberCombinedTransformation(
            syntaxSpans = syntaxSpans,
            searchMatches = matches,
            currentMatchIdx = matchIdx,
            queryLength = state.searchQuery.length,
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
                value = state.content,
                onValueChange = onContentChange,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(Spacing.md),
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

        PrimaryButton(
            text = "Guardar",
            onClick = onRequestSave,
            enabled = state.dirty && !state.loading
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
    kwColor: Color,
    strColor: Color,
    commentColor: Color,
    numColor: Color,
    annColor: Color,
    matchColor: Color,
    activeMatchColor: Color
): VisualTransformation = remember(
    syntaxSpans, searchMatches, currentMatchIdx, queryLength,
    kwColor, strColor, commentColor, numColor, annColor, matchColor, activeMatchColor
) {
    val hasSyntax = syntaxSpans.isNotEmpty()
    val hasSearch = queryLength > 0 && searchMatches.isNotEmpty()
    if (!hasSyntax && !hasSearch) return@remember VisualTransformation.None

    VisualTransformation { text ->
        val builder = AnnotatedString.Builder(text)

        // Sintaxis (color de texto)
        if (hasSyntax) {
            syntaxSpans.forEach { span ->
                val s = span.start.coerceAtMost(text.length)
                val e = span.end.coerceAtMost(text.length)
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
                val end = (start + queryLength).coerceAtMost(text.length)
                if (start < end) {
                    builder.addStyle(
                        SpanStyle(background = if (i == currentMatchIdx) activeMatchColor else matchColor),
                        start, end
                    )
                }
            }
        }

        TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
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
                currentDir = "/home/user/proj",
                entries = listOf(
                    FsEntry("src", true, "/home/user/proj/src"),
                    FsEntry("README.md", false, "/home/user/proj/README.md")
                ),
                openFilePath = "/home/user/proj/README.md",
                openFileName = "README.md",
                content = "# Hello\n\nEdit me.",
                dirty = true
            ),
            onClose = {}, onNavigate = {}, onGoUp = {}, onOpenFile = {},
            onContentChange = {}, onSave = {}, onCreateFile = {}, onCloseFile = {}, onClearError = {}
        )
    }
}
