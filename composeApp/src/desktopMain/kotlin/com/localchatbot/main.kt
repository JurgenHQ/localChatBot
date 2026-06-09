package com.localchatbot

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.localchatbot.di.AppContainer
import java.awt.Color as AwtColor
import java.awt.Dimension
import java.awt.Toolkit

private val isMacOs: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

fun main() {
    if (isMacOs) {
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
        System.setProperty("apple.awt.transparentTitleBar", "true")
        System.setProperty("apple.awt.fullWindowContent", "true")
    }

    // Icono en dock / taskbar durante desarrollo (en prod el .icns lo pone jpackage).
    runCatching {
        val iconUrl = object {}.javaClass.classLoader.getResource("AppIcon.png")
        if (iconUrl != null) {
            val image = Toolkit.getDefaultToolkit().getImage(iconUrl)
            // macOS: com.apple.eawt.Application.getApplication().setDockIconImage(image)
            // Lo hacemos por reflexión para no depender del módulo com.apple.
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            appClass.getMethod("setDockIconImage", java.awt.Image::class.java).invoke(app, image)
        }
    }

    application {
        val windowState = rememberWindowState(
            width = 1100.dp,
            height = 820.dp,
            position = WindowPosition(Alignment.Center),
        )
        // Reusamos el mismo AppContainer entre App() y cualquier configuración
        // de ventana (no creamos uno nuevo al recomponer).
        val container = remember {
            AppContainer().also { c ->
                // La persistencia de sesiones escribe con throttle de 250ms;
                // sin este hook, las últimas mutaciones se pierden al cerrar.
                Runtime.getRuntime().addShutdownHook(
                    Thread { c.chatRepository.flushPendingWrites() }
                )
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            // Título vacío en macOS para que no aparezca texto detrás de los
            // traffic lights.
            title = if (isMacOs) "" else "LocalChatBot",
        ) {
            window.minimumSize = Dimension(380, 600)
            if (isMacOs) {
                LaunchedEffect(Unit) {
                    // El rootPane es lo que macOS lee para decidir si extiende
                    // el contenido bajo el title bar.
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    // Background oscuro del JFrame: lo que se ve si por alguna
                    // razón el title bar NO se vuelve transparente (fallback).
                    window.background = AwtColor(0x0F, 0x0F, 0x10)
                }
            }
            App(
                container = container,
                topInset = if (isMacOs) 28.dp else 0.dp
            )
        }
    }
}
