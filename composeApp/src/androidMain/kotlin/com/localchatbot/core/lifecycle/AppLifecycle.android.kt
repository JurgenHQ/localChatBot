package com.localchatbot.core.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.localchatbot.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground = hay al menos una Activity en estado started. Se usa
 * `registerActivityLifecycleCallbacks` (sin dependencia de lifecycle-process).
 * Los config changes son seguros: la Activity nueva hace onStart antes del
 * onStop de la vieja, así que el contador nunca toca 0.
 */
private class AndroidAppLifecycle : AppLifecycle {
    private val _isForeground = MutableStateFlow(true)
    private val _backgroundCount = MutableStateFlow(0)
    override val isForeground: StateFlow<Boolean> = _isForeground
    override val backgroundCount: StateFlow<Int> = _backgroundCount

    private var startedActivities = 0

    init {
        val app = AppContextHolder.context.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                _isForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    _isForeground.value = false
                    _backgroundCount.value += 1
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

actual fun createAppLifecycle(): AppLifecycle = AndroidAppLifecycle()
