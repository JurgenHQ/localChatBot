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

/**
 * Diálogo para el patrón "el modelo redacta algo, vos lo revisás y recién ahí se aplica":
 * genera → muestra el borrador **editable** → aplicar o cancelar sin dejar rastro.
 *
 * Lo usan `/compact` (redacta el resumen del contexto) e `/init` (redacta `AGENTS.md`). Eran
 * dos composables con el mismo cuerpo palabra por palabra, distintos solo en los textos y en
 * qué campo del estado editaban; el precio de esa duplicación fue que un arreglo de
 * alineación en uno no llegó al otro. Los diálogos concretos siguen existiendo como envoltorios
 * finos: mantienen sus tipos de estado y el porqué de cada feature documentado en su sitio.
 *
 * Tres estados excluyentes: generando, error, o borrador listo para revisar.
 */
@Composable
fun DraftReviewDialog(
    title: String,
    /** Qué se está haciendo mientras se genera, p. ej. "Resumiendo la conversación…". */
    loadingText: String,
    /** Explica qué va a pasar al aplicar. Se muestra encima del borrador. */
    description: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    error: String?,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                when {
                    generating -> {
                        Text(
                            loadingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = Spacing.md)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                    error != null -> {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
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
            // Con el borrador vacío no hay nada que aplicar. En el caso de `/compact` no es
            // solo inútil: dejaría al modelo sin el detalle Y sin el resumen que lo sustituye.
            if (!generating && error == null) {
                TextButton(onClick = onApply, enabled = draft.isNotBlank()) {
                    Text("Aplicar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (error != null) "Cerrar" else "Cancelar") }
        }
    )
}
