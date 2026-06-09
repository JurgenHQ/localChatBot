package com.localchatbot

import android.app.Application
import com.localchatbot.core.car.CarNotificationPublisher
import com.localchatbot.di.AppContainer

class LocalChatBotApp : Application() {

    /**
     * Contenedor único a nivel de proceso. Antes lo creaba `App()` con
     * `remember {}`, pero el modo coche necesita acceder a él desde
     * BroadcastReceivers (reply de Android Auto) sin Activity viva.
     */
    val container: AppContainer by lazy { AppContainer() }

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
        CarNotificationPublisher.start(this, container.carMessageStore, container.applicationScope)
    }
}
