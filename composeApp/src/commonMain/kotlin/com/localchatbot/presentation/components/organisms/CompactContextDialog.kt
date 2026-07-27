package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.features.chat.ChatViewModel

/**
 * Diálogo de la compactación manual (`/compact`).
 *
 * El resumen se muestra **editable antes de aplicarse**: es el punto de la feature frente
 * al resumen rodante automático, que ocurre solo y a espaldas del usuario. Mientras el
 * diálogo está abierto no se modificó nada; cancelar no deja rastro.
 */
@Composable
fun CompactContextDialog(
    state: ChatViewModel.CompactState,
    onSummaryChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compactar contexto", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                when {
                    state.generating -> {
                        Text(
                            "Resumiendo la conversación…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = Spacing.md)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                    state.error != null -> {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {
                        Text(
                            "Se resumirán ${state.messageCount} mensajes (≈${state.estimatedTokensFreed} " +
                                "tokens menos por turno). Seguirán visibles en el chat: lo que cambia es " +
                                "que el modelo verá este resumen en vez de todo el detalle. Podés editarlo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.summary,
                            onValueChange = onSummaryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 320.dp)
                                .padding(top = Spacing.md),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Sin resumen no hay nada que aplicar: aplicar un texto vacío dejaría al
            // modelo sin el detalle Y sin el resumen que lo reemplaza.
            if (!state.generating && state.error == null) {
                TextButton(onClick = onApply, enabled = state.summary.isNotBlank()) {
                    Text("Aplicar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state.error != null) "Cerrar" else "Cancelar") }
        }
    )
}
