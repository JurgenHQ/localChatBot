package com.localchatbot.presentation.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.automation.AutomationScheduler
import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.ScheduledTask
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TaskUiItem(
    val task: ScheduledTask,
    val status: AutomationScheduler.RunStatus = AutomationScheduler.RunStatus()
)

data class TasksUiState(
    val tasks: List<TaskUiItem> = emptyList(),
    val showEditSheet: Boolean = false,
    val editingTask: ScheduledTask? = null
)

class TasksViewModel(
    private val preferences: PreferencesRepository,
    private val scheduler: AutomationScheduler
) : ViewModel() {

    private val _showEditSheet = MutableStateFlow(false)
    private val _editingTask = MutableStateFlow<ScheduledTask?>(null)

    val state: StateFlow<TasksUiState> = combine(
        preferences.preferences,
        scheduler.status,
        _showEditSheet,
        _editingTask
    ) { prefs, statuses, showEdit, editing ->
        TasksUiState(
            tasks = prefs.scheduledTasks.map { task ->
                TaskUiItem(task = task, status = statuses[task.id] ?: AutomationScheduler.RunStatus())
            },
            showEditSheet = showEdit,
            editingTask = editing
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TasksUiState())

    fun openAddSheet() {
        _editingTask.value = null
        _showEditSheet.value = true
    }

    fun openEditSheet(task: ScheduledTask) {
        _editingTask.value = task
        _showEditSheet.value = true
    }

    fun closeSheet() {
        _showEditSheet.value = false
        _editingTask.value = null
    }

    fun saveTask(task: ScheduledTask) = viewModelScope.launch {
        val current = preferences.current().scheduledTasks.toMutableList()
        val idx = current.indexOfFirst { it.id == task.id }
        if (idx >= 0) current[idx] = task else current.add(task)
        preferences.setScheduledTasks(current)
        closeSheet()
    }

    fun deleteTask(taskId: String) = viewModelScope.launch {
        val updated = preferences.current().scheduledTasks.filter { it.id != taskId }
        preferences.setScheduledTasks(updated)
    }

    fun duplicateTask(taskId: String) = viewModelScope.launch {
        val current = preferences.current().scheduledTasks
        val idx = current.indexOfFirst { it.id == taskId }
        if (idx < 0) return@launch
        // Copia con id propio, nombre distinguible y sin historial de ejecución
        // para que su programación se evalúe limpia. Deshabilitada por defecto
        // para que el usuario la revise antes de que empiece a dispararse.
        val copy = current[idx].copy(
            id = "task_${newId()}",
            name = "${current[idx].name} (copia)",
            enabled = false,
            lastRunEpochMs = null
        )
        val updated = current.toMutableList().apply { add(idx + 1, copy) }
        preferences.setScheduledTasks(updated)
    }

    fun toggleTask(taskId: String, enabled: Boolean) = viewModelScope.launch {
        val updated = preferences.current().scheduledTasks.map {
            if (it.id == taskId) it.copy(enabled = enabled) else it
        }
        preferences.setScheduledTasks(updated)
    }

    fun runNow(taskId: String) = scheduler.runNow(taskId)
}
