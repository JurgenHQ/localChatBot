package com.localchatbot.core.platform

actual object PlatformCapabilities {
    actual val voiceSupported: Boolean = true
    actual val isDesktop: Boolean = false
    actual val forceCloseHttpConnection: Boolean = false
    // CarPlay llegará en Fase 3 del plan coche (bloqueado por entitlement de Apple).
    actual val carModeSupported: Boolean = false
}
