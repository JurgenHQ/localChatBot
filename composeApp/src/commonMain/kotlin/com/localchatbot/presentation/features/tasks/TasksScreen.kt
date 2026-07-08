package com.localchatbot.presentation.features.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.automation.AutomationScheduler
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ScheduledTask
import com.localchatbot.presentation.components.atoms.SectionLabel
import com.localchatbot.presentation.components.molecules.SectionCard
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TasksContent(
            state = state,
            onClose = onClose,
            onAdd = viewModel::openAddSheet,
            onEdit = viewModel::openEditSheet,
            onDelete = viewModel::deleteTask,
            onToggle = viewModel::toggleTask,
            onRunNow = viewModel::runNow,
            onDuplicate = viewModel::duplicateTask
        )

        if (state.showEditSheet) {
            TaskEditSheet(
                editing = state.editingTask,
                onDismiss = viewModel::closeSheet,
                onSave = viewModel::saveTask
            )
        }
    }
}

@Composable
private fun TasksContent(
    state: TasksUiState,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ScheduledTask) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRunNow: (String) -> Unit,
    onDuplicate: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Volver",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "Tareas",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Agregar tarea",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        if (state.tasks.isEmpty()) {
            Text(
                "Sin tareas. Crea una tarea para que el agente la ejecute solo a una hora o cada cierto intervalo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SectionLabel("Tareas programadas")
            SectionCard {
                state.tasks.forEachIndexed { index, item ->
                    TaskRow(
                        item = item,
                        onEdit = { onEdit(item.task) },
                        onDelete = { onDelete(item.task.id) },
                        onToggle = { onToggle(item.task.id, it) },
                        onRunNow = { onRunNow(item.task.id) },
                        onDuplicate = { onDuplicate(item.task.id) }
                    )
                    if (index < state.tasks.lastIndex) HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        Text(
            "Cada tarea se ejecuta en una conversación nueva con el agente y sus tools (incluyendo MCP), " +
                "aprobando automáticamente las confirmaciones para poder correr sin ti. Requiere tener la app " +
                "de escritorio abierta a la hora programada.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TaskRow(
    item: TaskUiItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDuplicate: () -> Unit
) {
    val task = item.task
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    scheduleSummary(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val statusLine = statusLine(item.status)
                if (statusLine != null) {
                    Text(
                        statusLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.status.lastError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (item.status.running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onRunNow, enabled = !item.status.running) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Ejecutar ahora",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDuplicate) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Duplicar",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Editar",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private val ISO_DAY_LABELS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

private fun scheduleSummary(task: ScheduledTask): String = when {
    task.isInterval -> "Cada ${task.intervalMinutes} min"
    else -> {
        val time = "${task.hour.toString().padStart(2, '0')}:${task.minute.toString().padStart(2, '0')}"
        val days = if (task.daysOfWeek.isEmpty()) "todos los días"
        else task.daysOfWeek.sorted().joinToString(", ") { ISO_DAY_LABELS.getOrElse(it - 1) { "?" } }
        "Diario · $time · $days"
    }
}

private fun statusLine(status: AutomationScheduler.RunStatus): String? {
    if (status.running) return "Ejecutando…"
    val last = status.lastRunEpochMs ?: return null
    val when_ = formatTimestamp(last)
    return if (status.lastError != null) "Último error ($when_): ${status.lastError}"
    else "Última ejecución: $when_"
}

private fun formatTimestamp(epochMs: Long): String = runCatching {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val d = dt.dayOfMonth.toString().padStart(2, '0')
    val m = dt.monthNumber.toString().padStart(2, '0')
    val h = dt.hour.toString().padStart(2, '0')
    val min = dt.minute.toString().padStart(2, '0')
    "$d/$m $h:$min"
}.getOrDefault("")
