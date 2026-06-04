package com.localchatbot.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePicker(onResult: (ByteArray) -> Unit): ImagePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(scope, onResult) { DesktopImagePicker(scope, onResult) }
}

private class DesktopImagePicker(
    private val scope: CoroutineScope,
    private val onResult: (ByteArray) -> Unit,
) : ImagePickerLauncher {
    override fun launch() {
        scope.launch {
            val file = chooseFile() ?: return@launch
            val bytes = withContext(Dispatchers.IO) {
                runCatching { file.readBytes() }.getOrNull()
            }
            if (bytes != null) onResult(bytes)
        }
    }

    /**
     * Despacha el diálogo al EDT con [SwingUtilities.invokeLater] (que sí es
     * válido desde dentro del EDT) y bridgea el resultado de vuelta a la
     * corutina con un [CompletableDeferred]. Antes usábamos
     * `withContext(Dispatchers.Main) + invokeAndWait`, que tira
     * "Cannot call invokeAndWait from the event dispatcher thread" porque
     * en Compose Desktop `Dispatchers.Main` está bindeado al propio EDT.
     */
    private suspend fun chooseFile(): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Seleccionar imagen"
                fileFilter = FileNameExtensionFilter("Imágenes", "png", "jpg", "jpeg", "gif", "webp", "bmp")
            }
            val result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else {
                null
            }
            deferred.complete(result)
        }
        return deferred.await()
    }
}
