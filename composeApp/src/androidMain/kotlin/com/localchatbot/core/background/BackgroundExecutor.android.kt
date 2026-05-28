package com.localchatbot.core.background

import android.content.Intent
import androidx.core.content.ContextCompat
import com.localchatbot.AppContextHolder

private class AndroidBackgroundExecutor : BackgroundExecutor {
    private var running = false

    override fun start(reason: String) {
        if (running) return
        val context = AppContextHolder.context
        val intent = Intent(context, ChatForegroundService::class.java)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        running = true
    }

    override fun stop() {
        if (!running) return
        val context = AppContextHolder.context
        val intent = Intent(context, ChatForegroundService::class.java)
        runCatching { context.stopService(intent) }
        running = false
    }
}

actual fun createBackgroundExecutor(): BackgroundExecutor = AndroidBackgroundExecutor()
