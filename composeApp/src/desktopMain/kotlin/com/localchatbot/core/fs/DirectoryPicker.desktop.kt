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
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(null as Frame?, "Elegir workspace", FileDialog.LOAD)
            dialog.isVisible = true
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
            val result = if (dialog.file != null) File(dialog.directory, dialog.file) else null
            deferred.complete(result)
        }
        return deferred.await()
    }
}
