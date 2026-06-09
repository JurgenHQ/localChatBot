package com.localchatbot.presentation.components.util

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun WithContextMenu(
    items: () -> List<ContextMenuEntry>,
    content: @Composable () -> Unit
) {
    ContextMenuArea(
        items = { items().map { ContextMenuItem(it.label, it.onClick) } },
        content = content
    )
}
