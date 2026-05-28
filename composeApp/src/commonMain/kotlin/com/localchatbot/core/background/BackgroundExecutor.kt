package com.localchatbot.core.background

/**
 * Pide al sistema operativo que mantenga el proceso vivo durante operaciones
 * críticas (p. ej. streaming de la respuesta de un modelo) aunque la app se
 * vaya a background o la pantalla se bloquee.
 *
 * - Android: arranca un Foreground Service con notificación.
 * - iOS: usa `UIApplication.beginBackgroundTask(...)` para ganar ~30s extra.
 */
interface BackgroundExecutor {
    fun start(reason: String)
    fun stop()
}

expect fun createBackgroundExecutor(): BackgroundExecutor
