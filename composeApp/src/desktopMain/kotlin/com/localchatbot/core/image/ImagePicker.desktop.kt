package com.localchatbot.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

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

    private suspend fun chooseFile(): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val dialog = FileDialog(null as Frame?, "Seleccionar imagen", FileDialog.LOAD).apply {
                setFilenameFilter { _, name ->
                    name.lowercase().let {
                        it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                            it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp")
                    }
                }
            }
            dialog.isVisible = true
            val result = if (dialog.file != null) File(dialog.directory, dialog.file) else null
            deferred.complete(result)
        }
        return deferred.await()
    }
}
