package com.localchatbot.core.platform

import android.content.Intent
import android.net.Uri
import com.localchatbot.AppContextHolder

actual fun openUrl(url: String) {
    val context = AppContextHolder.context
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
