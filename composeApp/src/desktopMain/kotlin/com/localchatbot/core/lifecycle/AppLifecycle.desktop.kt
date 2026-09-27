package com.localchatbot.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * El proceso JVM no se suspende al minimizar la ventana: siempre foreground.
 * La rama de reanudación en SendMessageUseCase queda muerta en desktop.
 */
private class DesktopAppLifecycle : AppLifecycle {
    override val isForeground: StateFlow<Boolean> = MutableStateFlow(true)
    override val backgroundCount: StateFlow<Int> = MutableStateFlow(0)
}

actual fun createAppLifecycle(): AppLifecycle = DesktopAppLifecycle()
