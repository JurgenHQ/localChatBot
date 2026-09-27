package com.localchatbot.core.terminal

/** Sin terminal integrada en iOS: el sandbox no permite lanzar procesos. */
actual fun createShellSession(workingDir: String): ShellSession? = null
