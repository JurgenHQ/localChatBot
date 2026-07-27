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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.features.chat.ChatViewModel

/**
 * Diálogo de `/init`: propone el contenido de `AGENTS.md` **editable antes de escribirse**.
 * Mientras está abierto no se tocó nada del workspace; cancelar no deja rastro.
 */
@Composable
fun InitProjectDialog(
    state: ChatViewModel.InitProjectState,
    onContentChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generar AGENTS.md", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                when {
                    state.generating -> {
                        Text(
                            "Investigando el workspace…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CircularProgressIndicator(modifier = Modifier.padding(top = Spacing.md))
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
                            "Se creará AGENTS.md en la raíz del workspace con esto. Podés editarlo " +
                                "antes de aplicar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.content,
                            onValueChange = onContentChange,
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
            if (!state.generating && state.error == null) {
                TextButton(onClick = onApply, enabled = state.content.isNotBlank()) {
                    Text("Aplicar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state.error != null) "Cerrar" else "Cancelar") }
        }
    )
}
