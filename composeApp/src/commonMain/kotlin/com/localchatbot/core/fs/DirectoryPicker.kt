package com.localchatbot.core.fs

import androidx.compose.runtime.Composable

/**
 * Lanzador para pedir al usuario un directorio del sistema.
 * Solo Desktop tiene una implementación real (`JFileChooser`); Android e iOS
 * devuelven un launcher no-op porque la sección de Settings que lo usa está
 * gated por [com.localchatbot.core.platform.PlatformCapabilities.isDesktop].
 */
interface DirectoryPickerLauncher {
    fun launch()
}

/**
 * Devuelve un launcher recordado en la composición. Cuando el usuario elige
 * un directorio, [onResult] se invoca con el path absoluto. Si cancela, no
 * se llama.
 */
@Composable
expect fun rememberDirectoryPicker(onResult: (String) -> Unit): DirectoryPickerLauncher
