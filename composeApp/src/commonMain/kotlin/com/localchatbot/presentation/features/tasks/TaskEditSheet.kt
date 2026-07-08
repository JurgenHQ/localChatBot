package com.localchatbot.presentation.features.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.ScheduledTask
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SecondaryButton

private val DAY_LABELS = listOf("L", "M", "M", "J", "V", "S", "D") // ISO 1..7 (lun..dom)

@Composable
fun TaskEditSheet(
    editing: ScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var instructions by remember { mutableStateOf(editing?.instructions ?: "") }
    var daily by remember { mutableStateOf(editing?.isInterval != true) }
    var hourText by remember { mutableStateOf((editing?.hour ?: 9).toString().padStart(2, '0')) }
    var minuteText by remember { mutableStateOf((editing?.minute ?: 0).toString().padStart(2, '0')) }
    var intervalText by remember { mutableStateOf((editing?.intervalMinutes ?: 60).toString()) }
    val selectedDays = remember { mutableStateListOf<Int>().apply { editing?.daysOfWeek?.let { addAll(it) } } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            // pointerInput, no clickable: en desktop `clickable` reacciona a
            // Espacio/Enter (semántica de teclado), así que escribir un espacio en
            // el título/descripción cerraba el sheet. detectTapGestures no lo hace.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.surface)
                // Consume los taps sobre el sheet para que no lleguen al scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                if (editing == null) "Nueva tarea" else "Editar tarea",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Nombre (ej. Fichar entrada)"
            )

            FieldLabel("Instrucciones para el agente")
            AppTextField(
                value = instructions,
                onValueChange = { instructions = it },
                placeholder = "Qué debe hacer (ej. Abre la web de fichaje y marca mi entrada)",
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )

            FieldLabel("Programación")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Chip(label = "Diario", selected = daily, onClick = { daily = true })
                Chip(label = "Intervalo", selected = !daily, onClick = { daily = false })
            }

            if (daily) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    AppTextField(
                        value = hourText,
                        onValueChange = { v -> hourText = v.filter { it.isDigit() }.take(2) },
                        placeholder = "HH",
                        monospace = true,
                        modifier = Modifier.size(width = 64.dp, height = 52.dp)
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    AppTextField(
                        value = minuteText,
                        onValueChange = { v -> minuteText = v.filter { it.isDigit() }.take(2) },
                        placeholder = "MM",
                        monospace = true,
                        modifier = Modifier.size(width = 64.dp, height = 52.dp)
                    )
                }
                FieldLabel("Días (vacío = todos)")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    DAY_LABELS.forEachIndexed { index, label ->
                        val iso = index + 1
                        DayChip(
                            label = label,
                            selected = iso in selectedDays,
                            onClick = {
                                if (iso in selectedDays) selectedDays.remove(iso) else selectedDays.add(iso)
                            }
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text("Cada", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    AppTextField(
                        value = intervalText,
                        onValueChange = { v -> intervalText = v.filter { it.isDigit() }.take(5) },
                        placeholder = "60",
                        monospace = true,
                        modifier = Modifier.size(width = 88.dp, height = 52.dp)
                    )
                    Text("minutos", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(
                    text = "Cancelar",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = "Guardar",
                    onClick = {
                        onSave(
                            ScheduledTask(
                                id = editing?.id ?: "task_${newId()}",
                                name = name.trim().ifBlank { "Tarea" },
                                instructions = instructions.trim(),
                                enabled = editing?.enabled ?: true,
                                scheduleKind = if (daily) ScheduledTask.KIND_DAILY else ScheduledTask.KIND_INTERVAL,
                                intervalMinutes = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 60,
                                hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 9,
                                minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                                daysOfWeek = selectedDays.sorted().toList(),
                                // Reset del último run al cambiar la programación desde el editor
                                // para que el nuevo horario se evalúe limpio.
                                lastRunEpochMs = editing?.lastRunEpochMs
                            )
                        )
                    },
                    enabled = instructions.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm)
    )
}
