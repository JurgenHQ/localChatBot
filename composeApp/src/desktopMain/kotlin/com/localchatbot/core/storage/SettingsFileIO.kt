package com.localchatbot.core.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private const val DEFAULT_FILE_NAME = "localchatbot-settings.json"

@Composable
actual fun rememberSettingsExporter(
    onError: (String) -> Unit
): (String) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope, onError) {
        { json ->
            scope.launch {
                val file = chooseFile(save = true) ?: return@launch
                runCatching { file.writeText(json) }
                    .onFailure { onError("Error al guardar el archivo: ${it.message}") }
            }
        }
    }
}

@Composable
actual fun rememberSettingsImporter(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope, onResult, onError) {
        {
            scope.launch {
                val file = chooseFile(save = false) ?: return@launch
                runCatching { file.readText() }
                    .onSuccess(onResult)
                    .onFailure { onError("Error al leer el archivo: ${it.message}") }
            }
        }
    }
}

private suspend fun chooseFile(save: Boolean): File? {
    val deferred = CompletableDeferred<File?>()
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            dialogTitle = if (save) "Exportar configuración" else "Importar configuración"
            fileFilter = FileNameExtensionFilter("JSON (*.json)", "json")
            if (save) selectedFile = File(DEFAULT_FILE_NAME)
        }
        val option = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (option == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            // Al guardar, asegura la extensión .json
            if (save && !file.name.contains('.')) {
                file = File(file.parentFile, "${file.name}.json")
            }
            deferred.complete(file)
        } else {
            deferred.complete(null)
        }
    }
    return deferred.await()
}
