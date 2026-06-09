package com.localchatbot.core.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Convierte los mensajes de [CarMessageStore] en notificaciones `MessagingStyle`
 * que Android Auto muestra como conversación de mensajería: el sistema las lee
 * por TTS y ofrece responder por voz (acción Reply con RemoteInput).
 *
 * El asistente es un "contacto" ([ASSISTANT_NAME]); cada respuesta del modelo
 * llega como mensaje entrante suyo. Requisitos de Auto cubiertos:
 * - Acción Reply con `SEMANTIC_ACTION_REPLY` y `setShowsUserInterface(false)`.
 * - Acción Mark-as-read (invisible) con `SEMANTIC_ACTION_MARK_AS_READ`.
 */
object CarNotificationPublisher {

    const val CHANNEL_ID = "car_messages"
    const val NOTIFICATION_ID = 2001
    const val KEY_CAR_REPLY = "car_reply"

    /** Colecciona los mensajes entrantes y publica la notificación. Llamar una vez desde Application. */
    fun start(context: Context, store: CarMessageStore, scope: CoroutineScope) {
        ensureChannel(context)
        scope.launch {
            store.incoming.collect {
                post(context, store.conversation.value)
            }
        }
    }

    fun post(context: Context, conversation: List<CarMessage>) {
        if (conversation.isEmpty()) return

        val assistant = Person.Builder()
            .setName(ASSISTANT_NAME)
            .setBot(true)
            .build()
        val user = Person.Builder().setName("Tú").build()

        val style = NotificationCompat.MessagingStyle(user)
        conversation.forEach { msg ->
            style.addMessage(msg.text, msg.timestampEpochMs, assistant)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(buildReplyAction(context))
            .addInvisibleAction(buildMarkAsReadAction(context))
            .setAutoCancel(false)
            .build()

        runCatching {
            // Sin POST_NOTIFICATIONS concedido (API 33+) notify lanza SecurityException;
            // el modo coche simplemente no publica hasta que el usuario lo conceda en la app.
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun buildReplyAction(context: Context): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_CAR_REPLY)
            .setLabel("Responder")
            .build()
        val intent = Intent(context, CarReplyReceiver::class.java)
            .setAction(CarReplyReceiver.ACTION_REPLY)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_REPLY,
            intent,
            pendingFlagsMutable()
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Responder", pending
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun buildMarkAsReadAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, CarMarkAsReadReceiver::class.java)
            .setAction(CarMarkAsReadReceiver.ACTION_MARK_AS_READ)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_MARK_AS_READ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.checkbox_on_background, "Marcar como leído", pending
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /** RemoteInput exige PendingIntent mutable en API 31+. */
    private fun pendingFlagsMutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Modo coche",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mensajes del asistente en Android Auto."
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private const val ASSISTANT_NAME = "Asistente"
    private const val REQUEST_REPLY = 1
    private const val REQUEST_MARK_AS_READ = 2
}
