package com.localchatbot.presentation.components.molecules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.components.atoms.TypingIndicator
import com.localchatbot.presentation.components.util.SelectableOnDesktop

@Composable
fun ReasoningPanel(
    reasoning: String,
    live: Boolean,
    durationMs: Long? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Radius.md)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline

    if (live) {
        val scrollState = rememberScrollState()
        LaunchedEffect(reasoning) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(surfaceColor)
                .border(1.dp, outlineColor, shape)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "RAZONANDO",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = onSurface
                )
                TypingIndicator(dotSize = 6.dp, dotColor = onSurface)
            }
            Box(modifier = Modifier.heightIn(max = 140.dp)) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, surfaceColor)
                            )
                        )
                )
            }
        }
    } else {
        var expanded by remember { mutableStateOf(false) }
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(surfaceColor)
                .border(1.dp, outlineColor, shape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = buildString {
                        append("Ver el razonamiento")
                        if (durationMs != null) append("  ${formatDuration(durationMs)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Colapsar" else "Expandir",
                    tint = onSurface,
                    modifier = Modifier.size(16.dp).rotate(rotation)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                SelectableOnDesktop {
                    Text(
                        text = reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md)
                            .padding(bottom = Spacing.md)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val tenths = (ms / 100).toInt()
    val secs = tenths / 10
    val frac = tenths % 10
    return "${secs}.${frac}s"
}
