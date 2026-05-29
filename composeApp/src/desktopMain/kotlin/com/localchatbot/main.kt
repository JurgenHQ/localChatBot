package com.localchatbot

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    val windowState = rememberWindowState(
        width = 1100.dp,
        height = 820.dp,
        position = WindowPosition(Alignment.Center),
    )
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "LocalChatBot",
    ) {
        window.minimumSize = Dimension(380, 600)
        App()
    }
}
