package com.localchatbot.core.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renderiza una página web dentro de la app (Fase 1b). Cada plataforma usa su
 * motor nativo: `android.webkit.WebView` en Android, `WKWebView` en iOS. En
 * desktop no hay WebView embebido ligero, así que el `actual` muestra un panel
 * que invita a abrir la URL en el navegador del sistema.
 *
 * @param url URL a cargar. Puede cambiar entre recomposiciones (recarga).
 */
@Composable
expect fun PlatformWebView(url: String, modifier: Modifier)

/** true si la plataforma embebe la web dentro de la app (Android/iOS). */
expect val webViewSupported: Boolean
