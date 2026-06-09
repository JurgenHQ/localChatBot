package com.localchatbot.core.image

import androidx.compose.runtime.Composable

/** Lanzador de selección de imagen, agnóstico de plataforma. */
interface ImagePickerLauncher {
    fun launch()
}

/**
 * Devuelve un [ImagePickerLauncher] recordado en la composición. La selección
 * se entrega como bytes crudos de la imagen elegida.
 *
 * - Android / iOS: usa peekaboo (galería nativa).
 * - Desktop: `JFileChooser`.
 */
@Composable
expect fun rememberImagePicker(onResult: (ByteArray) -> Unit): ImagePickerLauncher
