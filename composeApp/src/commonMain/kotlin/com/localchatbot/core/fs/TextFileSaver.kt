package com.localchatbot.core.fs

/**
 * Guarda texto en un archivo elegido por el usuario mediante el diálogo nativo del SO.
 * Devuelve la ruta escrita, o null si el usuario canceló, falló la escritura o la
 * plataforma no lo soporta.
 *
 * Solo desktop tiene implementación real: en móvil exportar a un archivo suelto no es un
 * gesto natural (el portapapeles sí), así que los actuals devuelven null y la UI oculta la
 * acción con [com.localchatbot.core.platform.PlatformCapabilities.isDesktop].
 *
 * Es `suspend` a propósito: en Desktop el diálogo bloquea el hilo que lo abre, y las
 * llamadas vienen de un `onClick` de Compose — o sea el EDT de Swing. El actual salta a
 * IO antes de abrirlo.
 */
expect suspend fun saveTextFile(suggestedName: String, content: String): String?
