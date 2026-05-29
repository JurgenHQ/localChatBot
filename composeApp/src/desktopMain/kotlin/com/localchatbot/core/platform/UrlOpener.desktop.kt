package com.localchatbot.core.platform

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String) {
    runCatching {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
        }
    }
}
