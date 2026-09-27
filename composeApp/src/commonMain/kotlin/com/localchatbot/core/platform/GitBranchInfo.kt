package com.localchatbot.core.platform

/**
 * Devuelve la rama git actual de [workspaceDir], o null si la carpeta no es un
 * repositorio git (o git no está disponible).
 *
 * Solo tiene efecto en **desktop**, que es donde existe el workspace; los actuals de
 * móvil son no-op. Se usa únicamente para mostrar la rama en la barra del agente, nunca
 * para tools del modelo (esas pasan por `agent.runCommand` y el sandbox de workspace).
 */
expect suspend fun currentGitBranch(workspaceDir: String): String?
