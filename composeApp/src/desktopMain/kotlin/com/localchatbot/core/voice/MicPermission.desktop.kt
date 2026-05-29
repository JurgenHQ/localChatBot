package com.localchatbot.core.voice

/** Desktop no gestiona permisos de micrófono a nivel app; se asume concedido. */
actual suspend fun requestVoicePermissions(): Boolean = true
