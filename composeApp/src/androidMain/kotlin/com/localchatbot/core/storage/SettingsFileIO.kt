package com.localchatbot.core.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private const val DEFAULT_FILE_NAME = "localchatbot-settings.json"
private const val MIME_JSON = "application/json"

@Composable
actual fun rememberSettingsExporter(
    onError: (String) -> Unit
): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Guardamos el JSON pendiente hasta que SAF devuelva la URI elegida.
    val pending = remember { arrayOfNulls<String>(1) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_JSON)
    ) { uri ->
        val json = pending[0]
        pending[0] = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("No se pudo abrir el archivo")
            }.onFailure { onError("Error al guardar el archivo: ${it.message}") }
        }
    }

    return remember(launcher) {
        { json ->
            pending[0] = json
            launcher.launch(DEFAULT_FILE_NAME)
        }
    }
}

@Composable
actual fun rememberSettingsImporter(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                } ?: error("No se pudo abrir el archivo")
            }.onSuccess(onResult)
                .onFailure { onError("Error al leer el archivo: ${it.message}") }
        }
    }

    return remember(launcher) {
        { launcher.launch(arrayOf(MIME_JSON, "text/plain", "*/*")) }
    }
}
