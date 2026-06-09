package com.localchatbot.presentation.features.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.PromptTemplate
import kotlinx.datetime.Clock
import kotlin.random.Random

@Composable
fun PromptTemplatesSheet(
    templates: List<PromptTemplate>,
    onPick: (PromptTemplate) -> Unit,
    onSave: (List<PromptTemplate>) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            // pointerInput, no clickable: clickable añade semántica de teclado en
            // desktop (Espacio = click) y cerraba el sheet al escribir espacios.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.background)
                // Consume los taps para que no lleguen al scrim y cierren el sheet.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Plantillas de prompt",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable {
                            editing = PromptTemplate(id = newId(), title = "", body = "")
                        }
                        .padding(Spacing.sm)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Nueva", tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            if (templates.isEmpty()) {
                Text(
                    "Aún no hay plantillas. Crea la primera con el botón +.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(templates, key = { it.id }) { t ->
                        TemplateRow(
                            template = t,
                            onPick = { onPick(t) },
                            onEdit = { editing = t },
                            onDelete = { onSave(templates.filterNot { it.id == t.id }) }
                        )
                    }
                }
            }
        }
    }

    editing?.let { current ->
        TemplateEditorDialog(
            initial = current,
            onCancel = { editing = null },
            onSave = { updated ->
                val existing = templates.firstOrNull { it.id == updated.id }
                val next = if (existing == null) templates + updated
                else templates.map { if (it.id == updated.id) updated else it }
                onSave(next)
                editing = null
            }
        )
    }
}

@Composable
private fun TemplateRow(
    template: PromptTemplate,
    onPick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onPick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                template.title.ifBlank { "Sin título" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                template.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onEdit) { Text("Editar") }
        Box(
            modifier = Modifier.clickable(onClick = onDelete).padding(Spacing.xs)
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Borrar", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TemplateEditorDialog(
    initial: PromptTemplate,
    onCancel: () -> Unit,
    onSave: (PromptTemplate) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var body by remember { mutableStateOf(initial.body) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial.title.isBlank() && initial.body.isBlank()) "Nueva plantilla" else "Editar plantilla") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Cuerpo") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && body.isNotBlank(),
                onClick = { onSave(initial.copy(title = title.trim(), body = body.trim())) }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
    )
}

private fun newId(): String =
    Clock.System.now().toEpochMilliseconds().toString(36) +
        "-" + Random.nextInt(0, 1_000_000).toString(36)
