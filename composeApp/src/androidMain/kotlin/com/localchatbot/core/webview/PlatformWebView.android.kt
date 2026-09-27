package com.localchatbot.core.webview

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PlatformWebView(url: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // El cliente remoto usa WebSocket; el WebView de Android lo soporta
                // mientras JS y DOM storage estén activos.
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) webView.loadUrl(url)
        }
    )
}

actual val webViewSupported: Boolean = true
