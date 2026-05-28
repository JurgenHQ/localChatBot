package com.localchatbot.core.voice

/**
 * Solicita los permisos necesarios para grabar audio y reconocer voz.
 * Devuelve true si todos los permisos están concedidos.
 */
expect suspend fun requestVoicePermissions(): Boolean
