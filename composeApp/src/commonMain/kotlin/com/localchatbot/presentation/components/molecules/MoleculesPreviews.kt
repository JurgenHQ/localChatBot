package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.presentation.preview.PreviewData
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun ConnectionStatusBadgePreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ConnectionStatusBadge(status = ConnectionStatus.Connected(latencyMs = 42))
        ConnectionStatusBadge(status = ConnectionStatus.Checking)
        ConnectionStatusBadge(status = ConnectionStatus.Error("Sin conexión"))
        ConnectionStatusBadge(status = ConnectionStatus.Unknown)
    }
}

@Preview
@Composable
private fun LabeledFieldPreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        LabeledField(
            label = "Dirección IP",
            value = "192.168.1.42",
            onValueChange = {},
            placeholder = "192.168.1.42",
            monospace = true
        )
        LabeledField(
            label = "Puerto",
            value = "1234",
            onValueChange = {},
            placeholder = "1234",
            keyboardType = KeyboardType.Number,
            monospace = true,
            suffix = "/v1",
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        LabeledField(
            label = "Modelo",
            value = "llama-3.1-8b-instruct",
            onValueChange = {},
            monospace = true
        )
    }
}

@Preview
@Composable
private fun SuggestionChipPreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SuggestionChip("Explícame el patrón Repository", onClick = {})
        SuggestionChip("Revisa este snippet de Kotlin", onClick = {})
        SuggestionChip("Resume un texto largo", onClick = {})
    }
}

@Preview
@Composable
private fun MessageBubblePreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        MessageBubble(PreviewData.userMessage)
        MessageBubble(PreviewData.assistantMessage)
        MessageBubble(PreviewData.userFollowUp)
    }
}

@Preview
@Composable
private fun MessageBubbleDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    Column(
        modifier = Modifier.padding(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        MessageBubble(PreviewData.userMessage)
        MessageBubble(PreviewData.assistantMessage)
    }
}

@Preview
@Composable
private fun SessionRowPreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        PreviewData.sessionList.forEach { s ->
            SessionRow(session = s, onClick = {}, onDelete = {})
        }
    }
}

@Preview
@Composable
private fun SettingsRowPreview() = PreviewSurface {
    SectionCard(modifier = Modifier.padding(Spacing.lg)) {
        SettingsRow(title = "Dirección IP", onClick = {}, trailing = {
            Text("192.168.1.42", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.lg), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        SettingsRow(title = "Puerto", onClick = {}, trailing = {
            Text("1234", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.lg), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        SettingsRow(title = "Estado", onClick = {}, trailing = {
            Text("Conectado", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
}
