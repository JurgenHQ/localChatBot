package com.localchatbot.presentation.components.util

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

@Composable
actual fun SelectableOnDesktop(content: @Composable () -> Unit) {
    SelectionContainer { content() }
}
