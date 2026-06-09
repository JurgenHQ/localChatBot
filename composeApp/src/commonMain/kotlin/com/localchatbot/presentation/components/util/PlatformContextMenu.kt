package com.localchatbot.presentation.components.util

import androidx.compose.runtime.Composable

/**
 * Item de menú contextual abstracto (label + acción). Lo concreto (cómo se
 * dibuja) lo decide cada plataforma en [WithContextMenu].
 */
data class ContextMenuEntry(val label: String, val onClick: () -> Unit)

/**
 * En desktop, asocia un menú contextual nativo (click derecho) al [content].
 * El `SelectionContainer` que esté dentro AUTOMÁTICAMENTE añade su propio
 * item "Copy" al mismo menú, y ese item sí respeta la selección del usuario
 * — por eso preferimos esto antes que nuestro propio `DropdownMenu`.
 *
 * En móvil es un no-op (devuelve [content] directo); el menú contextual en
 * móvil se sigue manejando con long-press + `DropdownMenu` desde el llamador.
 */
@Composable
expect fun WithContextMenu(
    items: () -> List<ContextMenuEntry>,
    content: @Composable () -> Unit
)
