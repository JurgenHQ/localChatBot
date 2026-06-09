package com.localchatbot.core.platform

actual object PlatformCapabilities {
    // Voz aún no implementada en desktop (ver Fase 4 del plan).
    actual val voiceSupported: Boolean = false
    actual val isDesktop: Boolean = true
    actual val forceCloseHttpConnection: Boolean = true
    actual val carModeSupported: Boolean = false
}
