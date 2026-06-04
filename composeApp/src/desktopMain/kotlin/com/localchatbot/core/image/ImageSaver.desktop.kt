package com.localchatbot.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private class DesktopImageSaver : ImageSaver {
    override suspend fun saveToGallery(bytes: ByteArray, filename: String): Boolean {
        val target = chooseFile(filename) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { target.writeBytes(bytes) }.isSuccess
        }
    }

    /**
     * Igual que [ImagePicker]: usamos `invokeLater` + [CompletableDeferred]
     * para evitar el error "Cannot call invokeAndWait from the event
     * dispatcher thread" cuando la llamada parte desde el EDT (que es
     * donde está `Dispatchers.Main` en Compose Desktop).
     */
    private suspend fun chooseFile(filename: String): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Guardar imagen"
                selectedFile = File(filename)
            }
            val result = if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else {
                null
            }
            deferred.complete(result)
        }
        return deferred.await()
    }
}

actual fun createImageSaver(): ImageSaver = DesktopImageSaver()
