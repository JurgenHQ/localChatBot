package com.localchatbot

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(width = 420.dp, height = 840.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "LocalChatBot",
    ) {
        App()
    }
}
