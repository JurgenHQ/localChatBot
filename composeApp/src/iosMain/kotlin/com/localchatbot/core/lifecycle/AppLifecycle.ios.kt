package com.localchatbot.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * Observa las notificaciones de UIApplication. Se escucha también
 * DidBecomeActive (además de WillEnterForeground) para cubrir estados
 * "inactive" como el centro de control o una llamada entrante.
 * Los observers viven toda la vida de la app (AppContainer es singleton),
 * no hace falta removerlos.
 */
private class IosAppLifecycle : AppLifecycle {
    private val _isForeground = MutableStateFlow(true)
    private val _backgroundCount = MutableStateFlow(0)
    override val isForeground: StateFlow<Boolean> = _isForeground
    override val backgroundCount: StateFlow<Int> = _backgroundCount

    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            _isForeground.value = false
            _backgroundCount.value += 1
        }
        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> _isForeground.value = true }
        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> _isForeground.value = true }
    }
}

actual fun createAppLifecycle(): AppLifecycle = IosAppLifecycle()
