package com.localchatbot.core.platform

/** No-op en Android: las notificaciones de sistema y el dock son solo de escritorio. */
actual class SystemNotifier actual constructor() {
    actual fun notify(title: String, body: String) {
        // Sin efecto en móvil.
    }
}
