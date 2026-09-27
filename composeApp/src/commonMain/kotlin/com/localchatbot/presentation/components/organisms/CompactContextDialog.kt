package com.localchatbot.presentation.components.organisms

import androidx.compose.runtime.Composable
import com.localchatbot.presentation.features.chat.ChatViewModel

/**
 * Diálogo de la compactación manual (`/compact`).
 *
 * El resumen se muestra **editable antes de aplicarse**: es el punto de la feature frente
 * al resumen rodante automático, que ocurre solo y a espaldas del usuario. Mientras el
 * diálogo está abierto no se modificó nada; cancelar no deja rastro.
 *
 * El armazón lo pone [DraftReviewDialog], compartido con `/init`.
 */
@Composable
fun CompactContextDialog(
    state: ChatViewModel.CompactState,
    onSummaryChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    DraftReviewDialog(
        title = "Compactar contexto",
        loadingText = "Resumiendo la conversación…",
        description = "Se resumirán ${state.messageCount} mensajes (≈${state.estimatedTokensFreed} " +
            "tokens menos por turno). Seguirán visibles en el chat: lo que cambia es " +
            "que el modelo verá este resumen en vez de todo el detalle. Podés editarlo.",
        draft = state.summary,
        onDraftChange = onSummaryChange,
        generating = state.generating,
        error = state.error,
        onApply = onApply,
        onDismiss = onDismiss
    )
}
