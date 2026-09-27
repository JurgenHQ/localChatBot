package com.localchatbot.presentation.features.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onOpen() }

    // El VM no alcanza el portapapeles (solo existe dentro de Compose): pide, y acá se cumple.
    val clipboard = LocalClipboardManager.current
    val clipboardRequest by viewModel.clipboardRequest.collectAsStateWithLifecycle()
    LaunchedEffect(clipboardRequest) {
        val text = clipboardRequest ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(text))
        viewModel.consumeClipboardRequest()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EditorContent(
            state = state,
            onClose = onClose,
            onEntryClick = viewModel::onEntryClick,
            onRequestNewEntry = viewModel::requestNewEntry,
            onConfirmNewEntry = viewModel::confirmNewEntry,
            onCancelNewEntry = viewModel::cancelNewEntry,
            onRequestRename = viewModel::requestRename,
            onConfirmRename = viewModel::confirmRename,
            onCancelRename = viewModel::cancelRename,
            onRequestDelete = viewModel::requestDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
            onDuplicate = viewModel::duplicateEntry,
            onCopyPath = viewModel::copyPathToClipboard,
            onReveal = viewModel::revealInFileManager,
            onRefreshTree = viewModel::refreshTree,
            onRefreshNode = viewModel::refreshNode,
            onMoveEntry = viewModel::moveEntry,
            onContentChange = viewModel::onContentChange,
            onRequestCompletion = viewModel::requestCompletion,
            onDismissSuggestion = viewModel::dismissSuggestion,
            onSave = viewModel::save,
            onRequestSave = viewModel::requestSave,
            onConfirmSave = viewModel::confirmSave,
            onCancelSave = viewModel::cancelSave,
            onCloseFile = viewModel::closeFile,
            onClearError = viewModel::clearError,
            onClearScrollToLine = viewModel::clearScrollToLine,
            onToggleSearch = viewModel::toggleSearch,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onNextMatch = viewModel::nextMatch,
            onPrevMatch = viewModel::prevMatch,
            onTogglePreview = viewModel::togglePreviewMode
        )
    }
}
