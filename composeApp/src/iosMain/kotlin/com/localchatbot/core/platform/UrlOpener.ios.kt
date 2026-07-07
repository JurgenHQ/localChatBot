package com.localchatbot.core.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    // El selector viejo openURL: (sin options) es best-effort en apps con
    // scene lifecycle (iOS 13+) y puede no hacer nada sin lanzar error.
    // openURL:options:completionHandler: es la API vigente y sí abre Safari.
    UIApplication.sharedApplication.openURL(
        url = nsUrl,
        options = emptyMap<Any?, Any?>(),
        completionHandler = null
    )
}
