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

    /**
     * true si el cliente HTTP debe enviar `Connection: close` en cada chat
     * completion para evitar reusar una conexión que LM Studio ya cerró.
     *
     * **Solo desktop (CIO)**. En iOS (Darwin/NSURLSession) forzar Connection:close
     * provoca `-1005 / EPIPE` durante streaming porque Darwin cierra la conexión
     * más agresivamente de lo necesario.
     */
    val forceCloseHttpConnection: Boolean

    /**
     * true si el motor de render puede pintar texto en negro ignorando el color
     * especificado (bug de Compose Multiplatform en iOS, CMP-8028; sin fix al
     * menos hasta CMP 1.10). Cuando es true, el markdown del chat fuerza el
     * color del texto con un ColorFilter a nivel de capa.
     */
    val needsTextColorWorkaround: Boolean
}
