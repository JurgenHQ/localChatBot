package com.localchatbot.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual suspend fun saveTextFile(suggestedName: String, content: String): String? =
    withContext(Dispatchers.IO) {
        val file = chooseTarget(suggestedName) ?: return@withContext null
        // La extensión se agrega acá y no en el diálogo: en macOS `FileDialog` deja
        // escribir el nombre sin ella y quedaría un archivo sin asociar.
        val target = if (file.extension.equals("md", ignoreCase = true)) file else File(file.path + ".md")
        runCatching {
            target.writeText(content)
            target.path
        }.getOrNull()
    }

/**
 * macOS usa el `FileDialog` de AWT (es el panel nativo de guardado; `JFileChooser` ahí se
 * ve y se comporta como una ventana de Java ajena al sistema). El resto usa
 * `JFileChooser`, que en Windows/Linux es el que se integra mejor. Mismo criterio que el
 * selector de archivos de adjuntos.
 */
private fun chooseTarget(suggestedName: String): File? {
    val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
    return if (isMac) {
        val dialog = FileDialog(null as Frame?, "Guardar conversación", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        File(dir, name)
    } else {
        val chooser = JFileChooser().apply {
            dialogTitle = "Guardar conversación"
            selectedFile = File(suggestedName)
            fileFilter = FileNameExtensionFilter("Markdown (*.md)", "md")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
