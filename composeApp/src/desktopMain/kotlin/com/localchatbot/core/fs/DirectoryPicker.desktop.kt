package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

    /**
     * Compose Desktop bindea `Dispatchers.Main` al AWT EDT. Usar
     * `SwingUtilities.invokeAndWait` desde el propio EDT lanza
     * "Cannot call invokeAndWait from the event dispatcher thread".
     *
     * El patrón seguro es despachar el diálogo con `invokeLater` (que sí
     * funciona desde dentro del EDT — encola el bloque para la siguiente
     * iteración del event loop) y bridgear el resultado a la corutina
     * vía un [CompletableDeferred].
     */
    private suspend fun chooseDirectory(): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Elegir workspace"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
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
