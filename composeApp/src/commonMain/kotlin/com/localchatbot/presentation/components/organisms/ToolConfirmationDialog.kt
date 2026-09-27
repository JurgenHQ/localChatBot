package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.localchatbot.core.confirm.PendingConfirmation
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing

/**
 * Diálogo de aprobación humana para tools que lo requieren (fs/shell).
 * Cuando [PendingConfirmation.diff] está presente, renderiza líneas +/- con
 * colores verde/rojo. El detail (path, comando) se muestra debajo.
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
            val hasDiff = !pending.diff.isNullOrBlank()
            val hasDetail = !pending.detail.isNullOrBlank()

            if (!hasDiff && !hasDetail) {
                Text(
                    "El modelo quiere ejecutar esta acción.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    if (hasDiff) {
                        DiffBox(diff = pending.diff!!)
                    }
                    if (hasDetail) {
                        Text(
                            text = pending.detail!!,
                            modifier = Modifier.padding(top = if (hasDiff) Spacing.sm else 0.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

@Composable
private fun DiffBox(diff: String) {
    val addColor = Color(0xFF1B5E20)
    val removeColor = Color(0xFFB71C1C)
    val addBg = Color(0xFFE8F5E9)
    val removeBg = Color(0xFFFFEBEE)
    val hunkColor = Color(0xFF1565C0)
    val hunkBg = Color(0xFFE3F2FD)

    val annotated: AnnotatedString = remember(diff) {
        buildAnnotatedString {
            diff.lines().forEach { line ->
                when {
                    line.startsWith("+ ") || line == "+" -> {
                        withStyle(SpanStyle(color = addColor, background = addBg)) {
                            append(line)
                        }
                    }
                    line.startsWith("- ") || line == "-" -> {
                        withStyle(SpanStyle(color = removeColor, background = removeBg)) {
                            append(line)
                        }
                    }
                    line.startsWith("@@") -> {
                        withStyle(SpanStyle(color = hunkColor, background = hunkBg)) {
                            append(line)
                        }
                    }
                    else -> append(line)
                }
                append('\n')
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
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
}
