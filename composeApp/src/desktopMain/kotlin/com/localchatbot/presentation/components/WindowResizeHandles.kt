package com.localchatbot.presentation.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.Window as AwtWindow

private val HandleThickness = 5.dp

/**
 * Windows `undecorated` pierde el resize-por-borde nativo; esto lo recrea a mano
 * arrastrando `window.bounds` directamente (píxeles AWT — coinciden 1:1 con los deltas de
 * puntero de Compose Desktop, sin conversión de densidad). Se desactiva mientras la
 * ventana está maximizada, donde no aplica.
 */
@Composable
fun WindowResizeHandles(
    window: AwtWindow,
    windowState: WindowState,
    minWidth: Int = 380,
    minHeight: Int = 600,
) {
    if (windowState.placement == WindowPlacement.Maximized) return

    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        ResizeHandle(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(HandleThickness), Cursor.N_RESIZE_CURSOR) { _, dy ->
            resizeFromTop(window, dy, minHeight)
        }
        ResizeHandle(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(HandleThickness), Cursor.S_RESIZE_CURSOR) { _, dy ->
            resizeFromBottom(window, dy, minHeight)
        }
        ResizeHandle(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(HandleThickness), Cursor.W_RESIZE_CURSOR) { dx, _ ->
            resizeFromLeft(window, dx, minWidth)
        }
        ResizeHandle(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(HandleThickness), Cursor.E_RESIZE_CURSOR) { dx, _ ->
            resizeFromRight(window, dx, minWidth)
        }
        ResizeHandle(Modifier.align(Alignment.TopStart).size(HandleThickness * 2), Cursor.NW_RESIZE_CURSOR) { dx, dy ->
            resizeFromLeft(window, dx, minWidth)
            resizeFromTop(window, dy, minHeight)
        }
        ResizeHandle(Modifier.align(Alignment.TopEnd).size(HandleThickness * 2), Cursor.NE_RESIZE_CURSOR) { dx, dy ->
            resizeFromRight(window, dx, minWidth)
            resizeFromTop(window, dy, minHeight)
        }
        ResizeHandle(Modifier.align(Alignment.BottomStart).size(HandleThickness * 2), Cursor.SW_RESIZE_CURSOR) { dx, dy ->
            resizeFromLeft(window, dx, minWidth)
            resizeFromBottom(window, dy, minHeight)
        }
        ResizeHandle(Modifier.align(Alignment.BottomEnd).size(HandleThickness * 2), Cursor.SE_RESIZE_CURSOR) { dx, dy ->
            resizeFromRight(window, dx, minWidth)
            resizeFromBottom(window, dy, minHeight)
        }
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier, cursorType: Int, onDrag: (dx: Float, dy: Float) -> Unit) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursorType)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    )
}

private fun resizeFromRight(window: AwtWindow, dx: Float, minWidth: Int) {
    val b = window.bounds
    val newWidth = (b.width + dx).toInt().coerceAtLeast(minWidth)
    window.setSize(newWidth, b.height)
}

private fun resizeFromLeft(window: AwtWindow, dx: Float, minWidth: Int) {
    val b = window.bounds
    val newWidth = (b.width - dx).toInt().coerceAtLeast(minWidth)
    if (newWidth == b.width) return
    val newX = b.x + (b.width - newWidth)
    window.setBounds(newX, b.y, newWidth, b.height)
}

private fun resizeFromBottom(window: AwtWindow, dy: Float, minHeight: Int) {
    val b = window.bounds
    val newHeight = (b.height + dy).toInt().coerceAtLeast(minHeight)
    window.setSize(b.width, newHeight)
}

private fun resizeFromTop(window: AwtWindow, dy: Float, minHeight: Int) {
    val b = window.bounds
    val newHeight = (b.height - dy).toInt().coerceAtLeast(minHeight)
    if (newHeight == b.height) return
    val newY = b.y + (b.height - newHeight)
    window.setBounds(b.x, newY, b.width, newHeight)
}
