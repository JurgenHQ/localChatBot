package com.localchatbot.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

private class DesktopImageSaver : ImageSaver {
    override suspend fun saveToGallery(bytes: ByteArray, filename: String): Boolean {
        val target = chooseFile(filename) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { target.writeBytes(bytes) }.isSuccess
        }
    }

    private suspend fun chooseFile(filename: String): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val dialog = FileDialog(null as Frame?, "Guardar imagen", FileDialog.SAVE).apply {
                file = filename
            }
            dialog.isVisible = true
            val result = if (dialog.file != null) File(dialog.directory, dialog.file) else null
            deferred.complete(result)
        }
        return deferred.await()
    }
}

actual fun createImageSaver(): ImageSaver = DesktopImageSaver()
