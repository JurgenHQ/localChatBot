package com.localchatbot.presentation.features.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.AvailableModel
import com.localchatbot.presentation.components.atoms.SecondaryButton
import com.localchatbot.presentation.components.atoms.StatusDot
import com.localchatbot.presentation.components.molecules.ModelPickerList
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Selector de modelos abierto desde el subtítulo del top bar del chat. Con LM Studio
 * >= 0.4.0 permite cargar/descargar modelos; con otros backends degrada a selección simple.
 */
@Composable
fun ModelPickerSheet(
    viewModel: ModelPickerViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val streamingActive by viewModel.streamingActive.collectAsStateWithLifecycle()
    ModelPickerSheetContent(
        state = state,
        streamingActive = streamingActive,
        onModelClick = { viewModel.onModelClick(it, onDone = onDismiss) },
        onUnload = viewModel::onUnload,
        onConfirmSwap = { unloadPrevious -> viewModel.confirmSwap(unloadPrevious, onDone = onDismiss) },
        onDismissSwap = viewModel::dismissSwap,
        onDismiss = onDismiss
    )
}

@Composable
fun ModelPickerSheetContent(
    state: ModelPickerUiState,
    streamingActive: Boolean,
    onModelClick: (AvailableModel) -> Unit,
    onUnload: (AvailableModel) -> Unit,
    onConfirmSwap: (Boolean) -> Unit,
    onDismissSwap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Scrim con pointerInput en lugar de clickable: clickable añade semántica
    // de teclado en desktop (Espacio/Enter = click con foco) y cerraba el sheet
    // al escribir un espacio en los campos de texto.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.background)
                // Consume los taps para que no lleguen al scrim y cierren el sheet.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                "Modelo",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            when {
                state.loading -> Text(
                    "Cargando…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.canManage -> ManagedModelList(
                    state = state,
                    streamingActive = streamingActive,
                    onModelClick = onModelClick,
                    onUnload = onUnload
                )
                else -> {
                    ModelPickerList(
                        models = state.models.map { it.id },
                        selected = state.selectedModelId,
                        onSelect = { id ->
                            state.models.firstOrNull { it.id == id }?.let(onModelClick)
                        }
                    )
                    if (state.models.isNotEmpty()) {
                        Text(
                            "Este servidor no permite cargar/descargar modelos desde la app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            SecondaryButton(text = "Cancelar", onClick = onDismiss)
        }
    }

    state.pendingSwap?.let { swap ->
        val loadedNames = swap.loaded.joinToString(", ") { it.displayName ?: it.id }
        AlertDialog(
            onDismissRequest = onDismissSwap,
            title = { Text("Cargar ${swap.target.displayName ?: swap.target.id}") },
            text = {
                Text(
                    "$loadedNames ya está cargado en memoria. " +
                        "¿Descargarlo al cargar el nuevo modelo o mantener ambos?"
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmSwap(true) }) { Text("Descargar anterior") }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmSwap(false) }) { Text("Mantener ambos") }
            }
        )
    }
}

@Composable
private fun ManagedModelList(
    state: ModelPickerUiState,
    streamingActive: Boolean,
    onModelClick: (AvailableModel) -> Unit,
    onUnload: (AvailableModel) -> Unit
) {
    if (streamingActive) {
        Text(
            "Hay una respuesta en curso — espera a que termine para cargar o descargar modelos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (state.models.isEmpty()) {
        Text(
            "No hay modelos descargados en el servidor",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        state.models.forEach { model ->
            ModelRow(
                model = model,
                selected = model.id == state.selectedModelId,
                busy = model.id == state.busyModelId,
                enabled = state.busyModelId == null && !streamingActive,
                onClick = { onModelClick(model) },
                onUnload = { onUnload(model) }
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: AvailableModel,
    selected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onUnload: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            StatusDot(
                color = if (model.loaded == true) Color(0xFF2EBD66)
                else MaterialTheme.colorScheme.outlineVariant
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model.id,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
            val detail = when {
                busy -> "Cargando modelo… puede tardar"
                else -> listOfNotNull(
                    model.paramsString,
                    model.maxContextLength?.let { formatContext(it) },
                    if (model.loaded == true) "cargado" else "no cargado"
                ).joinToString(" · ")
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (model.loaded == true && !busy) {
            IconButton(onClick = onUnload, enabled = enabled) {
                Icon(
                    Icons.Outlined.Eject,
                    contentDescription = "Descargar modelo de memoria",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatContext(tokens: Int): String =
    if (tokens >= 1000) "${tokens / 1000}k ctx" else "$tokens ctx"

@Preview
@Composable
private fun ModelPickerManagedPreview() = PreviewSurface {
    ModelPickerSheetContent(
        state = ModelPickerUiState(
            loading = false,
            canManage = true,
            selectedModelId = "llama-3.1-8b-instruct",
            models = listOf(
                AvailableModel(
                    id = "llama-3.1-8b-instruct", displayName = "Llama 3.1 8B",
                    loaded = true, instanceIds = listOf("llama-3.1-8b-instruct"),
                    paramsString = "8B", maxContextLength = 131072
                ),
                AvailableModel(
                    id = "qwen2.5-coder-14b", displayName = "Qwen 2.5 Coder",
                    loaded = false, paramsString = "14B", maxContextLength = 32768
                )
            )
        ),
        streamingActive = false,
        onModelClick = {}, onUnload = {}, onConfirmSwap = {}, onDismissSwap = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun ModelPickerFallbackPreview() = PreviewSurface {
    ModelPickerSheetContent(
        state = ModelPickerUiState(
            loading = false,
            canManage = false,
            selectedModelId = "llama3:8b",
            models = listOf(AvailableModel(id = "llama3:8b"), AvailableModel(id = "mistral:7b"))
        ),
        streamingActive = false,
        onModelClick = {}, onUnload = {}, onConfirmSwap = {}, onDismissSwap = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun ModelPickerBusyPreview() = PreviewSurface {
    ModelPickerSheetContent(
        state = ModelPickerUiState(
            loading = false,
            canManage = true,
            busyModelId = "qwen2.5-coder-14b",
            models = listOf(
                AvailableModel(id = "qwen2.5-coder-14b", loaded = false, paramsString = "14B")
            )
        ),
        streamingActive = false,
        onModelClick = {}, onUnload = {}, onConfirmSwap = {}, onDismissSwap = {}, onDismiss = {}
    )
}
