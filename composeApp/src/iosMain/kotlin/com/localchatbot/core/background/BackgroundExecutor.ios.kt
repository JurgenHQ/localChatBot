package com.localchatbot.core.background

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * iOS concede ~30 s de gracia por CADA paso a background vía beginBackgroundTask,
 * pero un task expirado no se re-arma solo: si durante un turno largo el usuario
 * sale y vuelve varias veces, el segundo viaje se quedaría sin gracia. Por eso,
 * mientras el stream esté activo, se re-arma un task fresco en cada
 * DidEnterBackground y se libera al volver a foreground. Esa ventana es la que
 * permite completar el rollback + persistencia antes de la suspensión; la
 * reanudación del stream la hace SendMessageUseCase al volver a foreground.
 */
private class IosBackgroundExecutor : BackgroundExecutor {
    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var active = false
    private var reason = ""

    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> if (active) beginTask() }
        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> endTask() }
    }

    override fun start(reason: String) {
        if (active) return
        active = true
        this.reason = reason
        beginTask()
    }

    override fun stop() {
        if (!active) return
        active = false
        endTask()
    }

    private fun beginTask() {
        if (taskId != UIBackgroundTaskInvalid) return
        taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(reason) {
            // Handler de expiración: el sistema está a punto de suspendernos,
            // liberamos el task (se re-armará en el próximo DidEnterBackground).
            endTask()
        }
    }

    private fun endTask() {
        val id = taskId
        if (id == UIBackgroundTaskInvalid) return
        taskId = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(id)
    }
}

actual fun createBackgroundExecutor(): BackgroundExecutor = IosBackgroundExecutor()
