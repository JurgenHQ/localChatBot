package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.components.atoms.SectionLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelPickerList(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    emptyHint: String = "Verifica la conexión para listar modelos"
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SectionLabel("Modelos disponibles")
        when {
            loading -> Text(
                "Cargando…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            models.isEmpty() -> Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                models.forEach { name ->
                    ModelChip(
                        name = name,
                        selected = name == selected,
                        onClick = { onSelect(name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Text(
        text = name,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = fg,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    )
}
