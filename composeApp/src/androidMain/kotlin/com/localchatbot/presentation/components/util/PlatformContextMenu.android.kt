package com.localchatbot.presentation.components.util

import androidx.compose.runtime.Composable

@Composable
actual fun WithContextMenu(
    items: () -> List<ContextMenuEntry>,
    content: @Composable () -> Unit
) {
    content()
}
