package com.localchatbot.presentation.components.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Detecta click derecho (mouse secondary button). Solo dispara en desktop —
 * en móviles el evento nunca llega.
 */
fun Modifier.onRightClick(action: () -> Unit): Modifier = this.pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                action()
            }
        }
    }
}

/**
 * En desktop envuelve el contenido en `SelectionContainer` y suprime el menú
 * contextual nativo de Compose (el "Copy" automático) para que solo se vea
 * nuestro propio `DropdownMenu`. En móvil es un no-op (devuelve `content()`
 * directo) para no romper el long-press menú.
 */
@Composable
expect fun SelectableOnDesktop(content: @Composable () -> Unit)
