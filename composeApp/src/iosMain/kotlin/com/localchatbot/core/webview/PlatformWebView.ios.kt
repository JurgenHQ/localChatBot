package com.localchatbot.core.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(url: String, modifier: Modifier) {
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration())
        },
        update = { webView ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl != null) webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
        }
    )
}

actual val webViewSupported: Boolean = true
