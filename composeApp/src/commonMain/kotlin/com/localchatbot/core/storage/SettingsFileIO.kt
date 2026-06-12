package com.localchatbot.core.storage

import androidx.compose.runtime.Composable

/**
 * Selección de archivo para exportar/importar la configuración como JSON.
 * Mismo patrón `@Composable rememberX` que `DirectoryPicker`.
 *
 * - El exporter recibe el contenido JSON y abre un diálogo "guardar como"
 *   (nombre sugerido: `localchatbot-settings.json`).
 * - El importer abre un diálogo "abrir" y entrega el texto leído vía [onResult].
 * Si el usuario cancela, no se invoca ningún callback.
 */
@Composable
expect fun rememberSettingsExporter(
    onError: (String) -> Unit
): (String) -> Unit

@Composable
expect fun rememberSettingsImporter(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): () -> Unit
