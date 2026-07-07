package com.localchatbot.presentation.features.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onOpen() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EditorContent(
            state = state,
            onClose = onClose,
            onNavigate = viewModel::navigateTo,
            onGoUp = viewModel::goUp,
            onOpenFile = { path -> viewModel.openFile(path) },
            onContentChange = viewModel::onContentChange,
            onSave = viewModel::save,
            onRequestSave = viewModel::requestSave,
            onConfirmSave = viewModel::confirmSave,
            onCancelSave = viewModel::cancelSave,
            onCreateFile = viewModel::createFile,
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
