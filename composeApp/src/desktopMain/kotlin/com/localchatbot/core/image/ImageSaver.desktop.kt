package com.localchatbot.core.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private class DesktopImageSaver : ImageSaver {
    override suspend fun saveToGallery(bytes: ByteArray, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = chooseFile(filename) ?: return@withContext false
            runCatching { target.writeBytes(bytes) }.isSuccess
        }

    private suspend fun chooseFile(filename: String): File? = withContext(Dispatchers.Main) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Guardar imagen"
            selectedFile = File(filename)
        }
        var result: File? = null
        SwingUtilities.invokeAndWait {
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                result = chooser.selectedFile
            }
        }
        result
    }
}

actual fun createImageSaver(): ImageSaver = DesktopImageSaver()
