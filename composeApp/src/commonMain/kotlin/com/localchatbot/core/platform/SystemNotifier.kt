package com.localchatbot.core.platform

/**
 * Notificaciones nativas del SO + rebote del icono en dock/taskbar para llamar la
 * atención cuando termina una operación larga (respuesta de chat, tarea programada).
 *
 * Solo tiene efecto en **desktop**; los actuals de móvil son no-op (las notificaciones
 * de sistema en Android/iOS requieren permisos y canales que quedan fuera de alcance).
 *
 * [notify] es fire-and-forget: no suspende ni espera a que se muestre la notificación.
 * El llamador es responsable de comprobar la preferencia del usuario antes de invocarla.
 */
expect class SystemNotifier() {
    fun notify(title: String, body: String)
}
