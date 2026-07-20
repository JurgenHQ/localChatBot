package com.localchatbot.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

private val TitleBarHeight = 36.dp
private val ButtonWidth = 46.dp
private val CloseHoverColor = Color(0xFFE81123)

/**
 * Barra de título propia para Windows: la nativa choca con el tema oscuro de la app y
 * siempre trae el icono por defecto de Java (taza de café), y no hay forma confiable de
 * recolorearla desde fuera (ver historial de intentos con DwmSetWindowAttribute). Se usa
 * junto con `undecorated = true` en el `Window` de main.kt, envuelta en el mismo
 * `AppTheme` que el resto de la app para que siga el tema claro/oscuro elegido en
 * Settings (no queda forzada a oscuro como el título transparente de macOS). Sin icono
 * a propósito.
 */
@Composable
fun FrameWindowScope.DesktopTitleBar(
    windowState: WindowState,
    title: String,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    val glyphColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
    val hoverColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)

    WindowDraggableArea {
        Row(
            modifier = Modifier.fillMaxWidth().height(TitleBarHeight).background(MaterialTheme.colorScheme.background),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = glyphColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
            Box(Modifier.weight(1f, fill = true))
            CaptionButton(onClick = onMinimize, glyphColor = glyphColor, hoverColor = hoverColor) { color ->
                Canvas(Modifier.size(10.dp)) {
                    drawLine(
                        color,
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }
            }
            CaptionButton(onClick = onToggleMaximize, glyphColor = glyphColor, hoverColor = hoverColor) { color ->
                val maximized = windowState.placement == WindowPlacement.Maximized
                Canvas(Modifier.size(10.dp)) {
                    val stroke = Stroke(width = 1.dp.toPx())
                    if (maximized) {
                        val inset = size.width * 0.24f
                        drawRect(
                            color,
                            topLeft = Offset(0f, inset),
                            size = GeomSize(size.width - inset, size.height - inset),
                            style = stroke
                        )
                        drawRect(
                            color,
                            topLeft = Offset(inset, 0f),
                            size = GeomSize(size.width - inset, size.height - inset),
                            style = stroke
                        )
                    } else {
                        drawRect(color, style = stroke)
                    }
                }
            }
            CaptionButton(onClick = onClose, glyphColor = glyphColor, hoverColor = CloseHoverColor) { color ->
                Canvas(Modifier.size(10.dp)) {
                    val strokeWidth = 1.2.dp.toPx()
                    drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = strokeWidth)
                    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = strokeWidth)
                }
            }
        }
    }
}

@Composable
private fun CaptionButton(
    onClick: () -> Unit,
    glyphColor: Color,
    hoverColor: Color,
    icon: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(ButtonWidth)
            .hoverable(interactionSource)
            .background(if (hovered) hoverColor else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        icon(if (hovered && hoverColor == CloseHoverColor) Color.White else glyphColor)
    }
}
