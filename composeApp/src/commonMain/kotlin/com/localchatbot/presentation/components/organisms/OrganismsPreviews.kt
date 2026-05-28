package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun ChatTopBarPreview() = PreviewSurface {
    Column(modifier = Modifier.fillMaxWidth()) {
        ChatTopBar(
            title = "Nueva conversación",
            subtitle = "llama-3.1-8b-instruct",
            onMenuClick = {},
            onNewClick = {}
        )
        ChatTopBar(
            title = "Refactor de auth",
            subtitle = "llama-3.1-8b-instruct",
            onMenuClick = {},
            onNewClick = {}
        )
    }
}

@Preview
@Composable
private fun ChatTopBarDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    ChatTopBar(
        title = "Refactor de auth",
        subtitle = "llama-3.1-8b-instruct",
        onMenuClick = {},
        onNewClick = {}
    )
}

@Preview
@Composable
private fun AppBottomBarPreview() = PreviewSurface {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppBottomBar(selected = BottomTab.Chat, onSelect = {})
        AppBottomBar(selected = BottomTab.Settings, onSelect = {})
    }
}

@Preview
@Composable
private fun ChatComposerPreview() = PreviewSurface {
    Column(modifier = Modifier.fillMaxWidth()) {
        ChatComposer(value = "", onValueChange = {}, onSend = {}, onAttach = {})
        ChatComposer(value = "Hola modelo", onValueChange = {}, onSend = {}, onAttach = {})
        ChatComposer(value = "Enviando…", onValueChange = {}, onSend = {}, onAttach = {}, sending = true)
    }
}
