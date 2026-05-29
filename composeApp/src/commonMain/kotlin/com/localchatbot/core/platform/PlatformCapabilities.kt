package com.localchatbot.core.platform

/**
 * Capacidades que varían según la plataforma. Permite que la UI oculte o
 * adapte features que no existen en todos los targets (p. ej. voz en desktop).
 */
expect object PlatformCapabilities {
    /** true si el target ofrece reconocimiento de voz y TTS nativos. */
    val voiceSupported: Boolean

    /** true en targets de escritorio (JVM): habilita afinados de UX con mouse/teclado. */
    val isDesktop: Boolean
}
