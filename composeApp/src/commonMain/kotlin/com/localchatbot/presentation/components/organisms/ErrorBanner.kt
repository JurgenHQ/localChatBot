package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing

/**
 * Banner compacto para mostrar errores sobre el composer. Diseñado a propósito
 * con altura limitada (1 línea de resumen + acciones) para que un mensaje muy
 * largo — típico de errores HTTP con cuerpo o stack traces — no empuje el
 * composer fuera de la pantalla y bloquee la interacción con el teclado.
 *
 * Si el mensaje no cabe en una línea, aparece "Detalles" para abrir un diálogo
 * scrollable con el texto completo.
 */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    var detailsOpen by remember(message) { mutableStateOf(false) }

    val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val isLong = message.length > 120 || message.contains('\n')

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.height(20.dp)
        )
        Text(
            text = firstLine.ifBlank { "Error" },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isLong) {
            TextButton(
                onClick = { detailsOpen = true },
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
            ) {
                Text(
                    "Detalles",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(onClick = onDismiss)
                .padding(Spacing.xs),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cerrar",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.height(18.dp)
            )
        }
    }

    if (detailsOpen) {
        ErrorDetailsDialog(
            message = message,
            onCopy = onCopy,
            onDismiss = { detailsOpen = false }
        )
    }
}

@Composable
private fun ErrorDetailsDialog(
    message: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Detalles del error", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            // Caja con altura máxima para que en errores enormes la tarjeta del
            // diálogo no crezca fuera de pantalla; el contenido scrollea dentro.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(Spacing.md)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = {
            TextButton(onClick = {
                onCopy()
                onDismiss()
            }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.height(16.dp)
                    )
                    Text("Copiar")
                }
            }
        }
    )
}
