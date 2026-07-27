package com.localchatbot.core.platform

import java.awt.Desktop
import java.io.File

private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

/**
 * Lanza el explorador nativo con un proceso por plataforma en vez de con
 * `Desktop.open()` a secas, porque el comportamiento de AWT con directorios es
 * irregular entre sistemas (y en Linux depende del escritorio instalado).
 * `Desktop.open()` queda como último recurso.
 *
 * Nota Windows: `explorer.exe` devuelve código de salida 1 incluso cuando abre la
 * carpeta correctamente, así que aquí no se mira el exit code de nadie — se lanza el
 * proceso y se da por bueno; si falla al arrancar, salta la excepción y caemos al
 * fallback.
 *
 * Todo el trabajo va a un hilo daemon porque se llama desde el `onClick` de Compose, es
 * decir en el EDT: tanto los `isDirectory`/`isFile` (tocan disco; en una unidad de red o
 * desconectada pueden tardar segundos) como el arranque del proceso — `CreateProcess` en
 * Windows es bastante más caro que un `fork` — congelaban la UI mientras tanto.
 */
actual fun revealInFileManager(path: String) {
    Thread { openFolder(path) }
        .apply { isDaemon = true; name = "reveal-in-file-manager" }
        .start()
}

private fun openFolder(path: String) {
    val dir = File(path)
    // Si apunta a un archivo, abrimos su carpeta contenedora; si no existe, no hacemos nada
    // (el workspace pudo borrarse o estar en un disco desconectado).
    val target = when {
        dir.isDirectory -> dir
        dir.isFile -> dir.parentFile
        else -> null
    } ?: return

    val command = when {
        osName.contains("mac") -> listOf("open", target.absolutePath)
        osName.contains("win") -> listOf("explorer.exe", target.absolutePath)
        else -> listOf("xdg-open", target.absolutePath)
    }

    val launched = runCatching { ProcessBuilder(command).start() }.isSuccess
    if (launched) return

    runCatching {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) desktop.open(target)
    }
}
