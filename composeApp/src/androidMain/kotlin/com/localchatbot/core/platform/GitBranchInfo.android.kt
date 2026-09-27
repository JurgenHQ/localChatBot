package com.localchatbot.core.platform

/** No-op: el workspace y las herramientas de filesystem son solo de desktop. */
actual suspend fun currentGitBranch(workspaceDir: String): String? = null
