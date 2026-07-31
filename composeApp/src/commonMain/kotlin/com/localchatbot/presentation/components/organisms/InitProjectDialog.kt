package com.localchatbot.presentation.components.organisms

import androidx.compose.runtime.Composable
import com.localchatbot.presentation.features.chat.ChatViewModel

/**
 * Diálogo de `/init`: propone el contenido de `AGENTS.md` **editable antes de escribirse**.
 * Mientras está abierto no se tocó nada del workspace; cancelar no deja rastro.
 *
 * El armazón lo pone [DraftReviewDialog], compartido con `/compact`.
 */
@Composable
fun InitProjectDialog(
    state: ChatViewModel.InitProjectState,
    onContentChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    DraftReviewDialog(
        title = "Generar AGENTS.md",
        loadingText = "Investigando el workspace…",
        description = "Se creará AGENTS.md en la raíz del workspace con esto. Podés editarlo " +
            "antes de aplicar.",
        draft = state.content,
        onDraftChange = onContentChange,
        generating = state.generating,
        error = state.error,
        onApply = onApply,
        onDismiss = onDismiss
    )
}
