package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing

/**
 * Una entrada de la paleta. [hint] es el texto de la derecha: el atajo de teclado en las
 * acciones, o el modelo/preview en las conversaciones.
 */
data class PaletteCommand(
    val id: String,
    val label: String,
    val hint: String? = null,
    /** Agrupador que se muestra como encabezado ("Acciones", "Conversaciones"). */
    val group: String,
    val action: () -> Unit
)

/**
 * Paleta de comandos (Ctrl/Cmd+K): filtra acciones y conversaciones con una sola caja de
 * texto y ejecuta la seleccionada con Enter.
 *
 * Es lo que hace que la app se sienta rápida en escritorio, donde el recorrido alternativo
 * es abrir el drawer con el mouse y buscar a ojo. Se navega con ↑/↓, se ejecuta con Enter
 * y se cierra con Esc.
 */
@Composable
fun CommandPalette(
    commands: List<PaletteCommand>,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, commands) {
        if (query.isBlank()) commands
        else commands.filter { it.label.contains(query, ignoreCase = true) }
    }
    // Si el filtro se achica, el índice viejo puede quedar fuera de rango.
    val safeIndex = selectedIndex.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    LaunchedEffect(query) { selectedIndex = 0 }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun run(index: Int) {
        val cmd = filtered.getOrNull(index) ?: return
        // Cerrar ANTES de ejecutar: varias acciones abren otro overlay, y hacerlo al revés
        // dejaría el onDismiss cerrando lo que la acción acaba de abrir.
        onDismiss()
        cmd.action()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (filtered.isNotEmpty()) {
                                        selectedIndex = (safeIndex + 1) % filtered.size
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (filtered.isNotEmpty()) {
                                        selectedIndex = (safeIndex - 1 + filtered.size) % filtered.size
                                    }
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> { run(safeIndex); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "Buscar acción o conversación…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                if (filtered.isEmpty()) {
                    Text(
                        "Sin resultados",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(filtered, key = { _, cmd -> cmd.id }) { index, cmd ->
                            PaletteRow(
                                command = cmd,
                                showGroup = index == 0 || filtered[index - 1].group != cmd.group,
                                selected = index == safeIndex,
                                onClick = { run(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    command: PaletteCommand,
    showGroup: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column {
        if (showGroup) {
            Text(
                command.group.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.xs)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
                )
                // pointerInput y no clickable: en desktop `clickable` responde a Espacio,
                // y acá el foco está en el campo de búsqueda donde Espacio es un espacio.
                .pointerInput(command.id) { detectTapGestures { onClick() } }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                command.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            command.hint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
