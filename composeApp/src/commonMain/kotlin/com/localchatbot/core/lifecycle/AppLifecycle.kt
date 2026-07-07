package com.localchatbot.core.lifecycle

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Estado foreground/background de la app. Solo usa coroutines (sin Ktor ni
 * Compose) para que la capa domain pueda consumirlo — mismo criterio que
 * `isTransientNetworkError` en core/network.
 *
 * En móvil el SO suspende el proceso al pasar a background (iOS mata además
 * los sockets); [SendMessageUseCase] usa este estado para distinguir "el
 * stream falló porque nos suspendieron" de un fallo real del servidor y
 * reanudar al volver a foreground.
 */
interface AppLifecycle {
    val isForeground: StateFlow<Boolean>

    /**
     * Contador monotónico de transiciones a background. Permite detectar que
     * "la app pasó por background durante este intento" aunque el error del
     * socket se entregue cuando ya volvimos a foreground (NSURLSession vacía
     * los fallos pendientes al reanudar el proceso).
     */
    val backgroundCount: StateFlow<Int>

    /** Suspende hasta volver a foreground; inmediato si ya lo está. Cancelable. */
    suspend fun awaitForeground() {
        isForeground.first { it }
    }
}

expect fun createAppLifecycle(): AppLifecycle
