package com.localchatbot.core.background

import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

private class IosBackgroundExecutor : BackgroundExecutor {
    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    override fun start(reason: String) {
        if (taskId != UIBackgroundTaskInvalid) return
        taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(reason) {
            // Handler de expiración: el sistema está a punto de matarnos, limpiamos.
            stop()
        }
    }

    override fun stop() {
        val id = taskId
        if (id == UIBackgroundTaskInvalid) return
        taskId = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(id)
    }
}

actual fun createBackgroundExecutor(): BackgroundExecutor = IosBackgroundExecutor()
