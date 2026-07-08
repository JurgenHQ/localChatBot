package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

/**
 * Tarea automatizada que el agente ejecuta solo, a una hora o cada cierto
 * intervalo, mientras la app de escritorio esté abierta. Cada disparo crea una
 * sesión de chat nueva con [instructions] como primer mensaje y corre el loop de
 * tools completo (incluyendo MCP), con las confirmaciones auto-aprobadas para que
 * pueda correr sin el usuario delante.
 *
 * Programación ([scheduleKind]):
 * - [KIND_DAILY]: se dispara a las [hour]:[minute] de cada día. Si [daysOfWeek]
 *   no está vacío, sólo en esos días (ISO: 1=lunes … 7=domingo).
 * - [KIND_INTERVAL]: se dispara cada [intervalMinutes] minutos.
 *
 * [scheduleKind] es String (no enum) para no romper la deserialización si en el
 * futuro se agregan tipos; todos los campos tienen default para que configs
 * persistidas con versiones anteriores sigan deserializando.
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val instructions: String,
    val enabled: Boolean = true,
    val scheduleKind: String = KIND_DAILY,
    /** Sólo para [KIND_INTERVAL]. Minutos entre disparos. */
    val intervalMinutes: Int = 60,
    /** Sólo para [KIND_DAILY]. Hora local (0-23). */
    val hour: Int = 9,
    /** Sólo para [KIND_DAILY]. Minuto local (0-59). */
    val minute: Int = 0,
    /** Sólo para [KIND_DAILY]. Días ISO permitidos (1=lun … 7=dom); vacío = todos. */
    val daysOfWeek: List<Int> = emptyList(),
    /** Epoch ms del último disparo. Lo escribe el scheduler; evita re-disparos. */
    val lastRunEpochMs: Long? = null
) {
    val isDaily: Boolean get() = scheduleKind == KIND_DAILY
    val isInterval: Boolean get() = scheduleKind == KIND_INTERVAL

    companion object {
        const val KIND_DAILY = "daily"
        const val KIND_INTERVAL = "interval"
    }
}
