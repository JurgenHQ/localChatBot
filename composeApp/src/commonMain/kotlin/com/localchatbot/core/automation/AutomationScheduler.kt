package com.localchatbot.core.automation

import com.localchatbot.core.confirm.AutoApproveConfirmations
import com.localchatbot.domain.model.ScheduledTask
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Dispara las [ScheduledTask] configuradas mientras la app esté abierta (solo se
 * arranca en desktop). Cada disparo abre una sesión de chat nueva con las
 * instrucciones de la tarea y corre el loop de tools completo vía
 * [SendMessageUseCase], con las confirmaciones auto-aprobadas ([AutoApproveConfirmations])
 * para poder correr sin el usuario delante.
 *
 * Los disparos son secuenciales (un [tick] espera a que termine cada tarea due
 * antes de la siguiente) para no saturar el modelo local. El [status] efímero
 * alimenta el feedback de la UI; el `lastRunEpochMs` persiste en preferencias
 * para evitar re-disparos entre reinicios.
 */
class AutomationScheduler(
    private val prefs: PreferencesRepository,
    private val chats: ChatRepository,
    private val createSession: CreateSessionUseCase,
    private val sendMessage: SendMessageUseCase,
    private val scope: CoroutineScope,
    private val tickIntervalMs: Long = 30_000L
) {
    data class RunStatus(
        val running: Boolean = false,
        val lastRunEpochMs: Long? = null,
        val lastError: String? = null,
        val lastSessionId: String? = null
    )

    private val _status = MutableStateFlow<Map<String, RunStatus>>(emptyMap())
    val status: StateFlow<Map<String, RunStatus>> = _status.asStateFlow()

    private var loopJob: Job? = null

    /** Serializa la ejecución: nunca dos runs de la misma o distinta tarea a la vez. */
    private val runMutex = Mutex()

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Dispara una tarea ahora mismo, ignorando su programación (botón "ejecutar ya"). */
    fun runNow(taskId: String) {
        scope.launch {
            val task = prefs.current().scheduledTasks.firstOrNull { it.id == taskId } ?: return@launch
            runTask(task)
        }
    }

    private suspend fun tick() {
        val now = Clock.System.now().toEpochMilliseconds()
        val due = prefs.current().scheduledTasks.filter { it.enabled && isDue(it, now) }
        for (task in due) {
            // Re-lee la tarea por si cambió mientras corría otra; salta si desapareció.
            val fresh = prefs.current().scheduledTasks.firstOrNull { it.id == task.id } ?: continue
            if (fresh.enabled && isDue(fresh, Clock.System.now().toEpochMilliseconds())) {
                runTask(fresh)
            }
        }
    }

    private suspend fun runTask(task: ScheduledTask) = runMutex.withLock {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        _status.update { it + (task.id to (it[task.id] ?: RunStatus()).copy(running = true, lastError = null)) }
        var sessionId: String? = null
        try {
            val session = createSession()
            sessionId = session.id
            chats.updateTitle(session.id, "⏰ ${task.name}")
            val result = withContext(AutoApproveConfirmations()) {
                sendMessage(session.id, task.instructions)
            }
            markRun(task.id, startedAt)
            _status.update {
                it + (task.id to RunStatus(
                    running = false,
                    lastRunEpochMs = startedAt,
                    lastError = result.exceptionOrNull()?.message?.take(160),
                    lastSessionId = session.id
                ))
            }
        } catch (e: CancellationException) {
            _status.update { it + (task.id to (it[task.id] ?: RunStatus()).copy(running = false)) }
            throw e
        } catch (e: Exception) {
            markRun(task.id, startedAt)
            _status.update {
                it + (task.id to RunStatus(
                    running = false,
                    lastRunEpochMs = startedAt,
                    lastError = (e.message ?: "Error").take(160),
                    lastSessionId = sessionId
                ))
            }
        }
    }

    private suspend fun markRun(taskId: String, ts: Long) {
        val updated = prefs.current().scheduledTasks.map {
            if (it.id == taskId) it.copy(lastRunEpochMs = ts) else it
        }
        prefs.setScheduledTasks(updated)
    }

    private fun isDue(task: ScheduledTask, now: Long): Boolean = when (task.scheduleKind) {
        ScheduledTask.KIND_INTERVAL -> {
            val last = task.lastRunEpochMs
            val intervalMs = task.intervalMinutes.coerceAtLeast(1) * 60_000L
            last == null || now - last >= intervalMs
        }
        ScheduledTask.KIND_DAILY -> {
            val tz = TimeZone.currentSystemDefault()
            val nowDt = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz)
            val dayAllowed = task.daysOfWeek.isEmpty() || nowDt.dayOfWeek.isoDayNumber in task.daysOfWeek
            if (!dayAllowed) {
                false
            } else {
                val scheduledToday = LocalDateTime(
                    nowDt.year, nowDt.monthNumber, nowDt.dayOfMonth,
                    task.hour.coerceIn(0, 23), task.minute.coerceIn(0, 59)
                ).toInstant(tz).toEpochMilliseconds()
                // Due si ya pasó la hora de hoy y no se corrió después de esa hora.
                now >= scheduledToday && (task.lastRunEpochMs?.let { it < scheduledToday } ?: true)
            }
        }
        else -> false
    }
}
