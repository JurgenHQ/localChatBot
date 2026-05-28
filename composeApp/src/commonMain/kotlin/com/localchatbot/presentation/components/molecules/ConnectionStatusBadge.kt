package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.presentation.components.atoms.StatusDot

@Composable
fun ConnectionStatusBadge(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (label, color, bg) = when (status) {
        is ConnectionStatus.Connected -> Triple(
            "Conexión exitosa",
            Color(0xFF2EBD66),
            Color(0xFFE7F6EC)
        )
        is ConnectionStatus.Checking -> Triple("Verificando…", Color(0xFFB07E13), Color(0xFFFBF1D9))
        is ConnectionStatus.Error -> Triple(status.message, Color(0xFFE84A4A), Color(0xFFFBE6E6))
        ConnectionStatus.Unknown -> Triple("Sin verificar", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color = color)
        Spacer(Modifier.size(Spacing.sm))
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        if (status is ConnectionStatus.Connected) {
            Spacer(Modifier.weight(1f))
            Text(
                "${status.latencyMs} ms",
                color = color,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}
