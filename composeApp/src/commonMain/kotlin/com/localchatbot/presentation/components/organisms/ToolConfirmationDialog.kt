package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localchatbot.core.confirm.PendingConfirmation
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing

/**
 * Diálogo de aprobación humana para tools que lo requieren (fs/shell).
 * El detail (path, comando, diff) se muestra en monospace scrollable.
 */
@Composable
fun ToolConfirmationDialog(
    pending: PendingConfirmation,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                pending.title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            val detail = pending.detail
            if (detail.isNullOrBlank()) {
                Text(
                    "El modelo quiere ejecutar esta acción.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(Spacing.md)
                ) {
                    Text(
                        text = detail,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text("Aprobar", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Rechazar", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
