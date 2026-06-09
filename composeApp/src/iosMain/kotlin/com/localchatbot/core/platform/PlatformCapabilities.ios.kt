package com.localchatbot.core.platform

actual object PlatformCapabilities {
    actual val voiceSupported: Boolean = true
    actual val isDesktop: Boolean = false
    actual val forceCloseHttpConnection: Boolean = false
}
