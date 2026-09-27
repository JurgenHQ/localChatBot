package com.localchatbot.core.webview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localchatbot.core.platform.openUrl

/**
 * Desktop normalmente ES el host del acceso remoto, así que el visor embebido es
 * secundario. No hay WebView ligero en la JVM (JCEF/JavaFX serían pesados), de
 * modo que se ofrece abrir la URL en el navegador del sistema.
 */
@Composable
actual fun PlatformWebView(url: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text(
            "El visor embebido no está disponible en escritorio.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = { if (url.isNotBlank()) openUrl(url) }, enabled = url.isNotBlank()) {
            Text("Abrir en el navegador")
        }
    }
}

actual val webViewSupported: Boolean = false
