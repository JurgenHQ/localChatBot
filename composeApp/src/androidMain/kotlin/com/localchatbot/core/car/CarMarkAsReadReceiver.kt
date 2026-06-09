package com.localchatbot.core.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Acción "marcar como leído" requerida por Android Auto en apps de mensajería.
 * Retira la notificación; el hilo persiste en `CarMessageStore`/`ChatRepository`.
 */
class CarMarkAsReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_AS_READ) return
        CarNotificationPublisher.cancel(context)
    }

    companion object {
        const val ACTION_MARK_AS_READ = "com.localchatbot.car.ACTION_MARK_AS_READ"
    }
}
