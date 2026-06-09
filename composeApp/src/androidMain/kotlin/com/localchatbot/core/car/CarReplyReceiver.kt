package com.localchatbot.core.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.localchatbot.LocalChatBotApp
import com.localchatbot.core.util.newId
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Recibe el texto dictado por voz desde Android Auto (acción Reply con
 * RemoteInput) y lo envía al LLM vía `CarSessionManager`.
 *
 * El trabajo va al `applicationScope` con el foreground service activo
 * (`backgroundExecutor`) para que el proceso sobreviva los segundos/minutos
 * que tarde el modelo — un BroadcastReceiver por sí solo no puede esperar.
 * La respuesta llega como nueva notificación vía [CarNotificationPublisher]
 * (que colecciona `CarMessageStore.incoming`); los errores se publican
 * también al store para que Auto los lea por voz, nunca en silencio.
 */
class CarReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CarNotificationPublisher.KEY_CAR_REPLY)
            ?.toString()?.trim()
            .orEmpty()
        if (text.isEmpty()) return

        val container = (context.applicationContext as LocalChatBotApp).container
        container.backgroundExecutor.start("car-reply")
        container.applicationScope.launch {
            try {
                val result = container.carSessionManager.handleUserUtterance(text)
                result.exceptionOrNull()?.let { failure ->
                    container.carMessageStore.publish(
                        CarMessage(
                            id = newId(),
                            text = failure.message ?: CarSessionManager.SPOKEN_CONNECTION_ERROR,
                            timestampEpochMs = Clock.System.now().toEpochMilliseconds()
                        )
                    )
                }
            } finally {
                container.backgroundExecutor.stop()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.localchatbot.car.ACTION_REPLY"
    }
}
