package com.localchatbot.core.platform

/**
 * Abre [path] en el explorador de archivos del sistema (Finder en macOS, Explorador en
 * Windows, el gestor por defecto en Linux).
 *
 * Solo tiene efecto en **desktop**, que es donde existe el workspace; los actuals de
 * móvil son no-op. Es fire-and-forget y nunca lanza: si la ruta no existe o el sistema
 * no puede abrirla, no pasa nada.
 */
expect fun revealInFileManager(path: String)
