package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
actual fun rememberDirectoryPicker(onResult: (String) -> Unit): DirectoryPickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(scope, onResult) { DesktopDirectoryPicker(scope, onResult) }
}

private class DesktopDirectoryPicker(
    private val scope: CoroutineScope,
    private val onResult: (String) -> Unit
) : DirectoryPickerLauncher {
    override fun launch() {
        scope.launch {
            val dir = chooseDirectory() ?: return@launch
            onResult(dir.absolutePath)
        }
    }

    private suspend fun chooseDirectory(): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            val result = if (isMac) {
                // FileDialog nativo: solo macOS soporta selección de carpetas
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                val dialog = FileDialog(null as Frame?, "Elegir workspace", FileDialog.LOAD)
                dialog.isVisible = true
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                if (dialog.file != null) File(dialog.directory, dialog.file) else null
            } else {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Elegir workspace"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile
                } else null
            }
            deferred.complete(result)
        }
        return deferred.await()
    }
}
