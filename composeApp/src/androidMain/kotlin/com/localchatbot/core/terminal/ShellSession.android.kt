package com.localchatbot.core.terminal

/** Sin terminal integrada en Android: no hay shell de usuario al que conectarse. */
actual fun createShellSession(workingDir: String): ShellSession? = null
