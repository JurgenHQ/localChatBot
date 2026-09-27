package com.localchatbot.presentation.features.mcp

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SecondaryButton

private class KvPair(key: String = "", value: String = "") {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

@Composable
fun McpServerEditSheet(
    editing: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var url by remember { mutableStateOf(editing?.url ?: "") }
    // Stdio solo tiene sentido en desktop (lanza un proceso local).
    val stdioAvailable = PlatformCapabilities.isDesktop
    var stdio by remember { mutableStateOf(stdioAvailable && editing?.isStdio == true) }
    var command by remember { mutableStateOf(editing?.command ?: "") }
    var argsText by remember { mutableStateOf(editing?.args?.joinToString(" ") ?: "") }
    val headerPairs = remember {
        mutableStateListOf<KvPair>().apply {
            editing?.headers?.forEach { (k, v) -> add(KvPair(k, v)) }
        }
    }
    val envPairs = remember {
        mutableStateListOf<KvPair>().apply {
            editing?.env?.forEach { (k, v) -> add(KvPair(k, v)) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            // pointerInput, no clickable: en desktop `clickable` reacciona a
            // Espacio/Enter (semántica de teclado) y cerraría el sheet al teclear.
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
                if (editing == null) "Agregar servidor MCP" else "Editar servidor MCP",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Nombre (ej. Context7)"
            )

            if (stdioAvailable) {
                TransportSelector(stdio = stdio, onChange = { stdio = it })
            }

            if (!stdio) {
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "URL del servidor MCP (ej. https://mcp.context7.com/mcp)"
                )

                KeyValueEditor(
                    title = "Headers (opcional — para autenticación)",
                    pairs = headerPairs,
                    keyPlaceholder = "Header (ej. Authorization)",
                    valuePlaceholder = "valor (ej. Bearer xxx)",
                    addLabel = "Agregar header"
                )
            } else {
                AppTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = "Comando (ej. npx)",
                    monospace = true
                )
                AppTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    placeholder = "Argumentos (ej. -y @modelcontextprotocol/server-filesystem /tmp)",
                    monospace = true
                )
                Text(
                    "Los argumentos se separan por espacios (sin soporte de comillas).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KeyValueEditor(
                    title = "Variables de entorno (opcional)",
                    pairs = envPairs,
                    keyPlaceholder = "Variable (ej. API_KEY)",
                    valuePlaceholder = "valor",
                    addLabel = "Agregar variable"
                )
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
                        val trimmedUrl = url.trim()
                        val trimmedCommand = command.trim()
                        onSave(
                            McpServerConfig(
                                id = editing?.id ?: "mcp_${newId()}",
                                name = name.trim().ifBlank { if (stdio) trimmedCommand else trimmedUrl },
                                url = trimmedUrl,
                                headers = headerPairs.toMap(),
                                enabled = editing?.enabled ?: true,
                                transport = if (stdio) McpServerConfig.TRANSPORT_STDIO else McpServerConfig.TRANSPORT_HTTP,
                                command = trimmedCommand.takeIf { stdio && it.isNotBlank() },
                                args = if (stdio) argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() } else emptyList(),
                                env = if (stdio) envPairs.toMap() else emptyMap()
                            )
                        )
                    },
                    enabled = if (stdio) command.isNotBlank() else url.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Convierte la lista de pares a un Map, descartando los que tengan key en blanco. */
private fun List<KvPair>.toMap(): Map<String, String> =
    filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value }

/** Selector HTTP | Stdio (solo desktop). */
@Composable
private fun TransportSelector(stdio: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            "Transporte",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TransportChip(label = "HTTP", selected = !stdio, onClick = { onChange(false) })
            TransportChip(label = "Stdio (proceso local)", selected = stdio, onClick = { onChange(true) })
        }
    }
}

@Composable
private fun TransportChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    )
}

@Composable
private fun KeyValueEditor(
    title: String,
    pairs: MutableList<KvPair>,
    keyPlaceholder: String,
    valuePlaceholder: String,
    addLabel: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        pairs.forEachIndexed { index, pair ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                AppTextField(
                    value = pair.key,
                    onValueChange = { pair.key = it },
                    placeholder = keyPlaceholder,
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                AppTextField(
                    value = pair.value,
                    onValueChange = { pair.value = it },
                    placeholder = valuePlaceholder,
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { pairs.removeAt(index) }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Quitar",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable { pairs.add(KvPair()) }
                .padding(vertical = Spacing.xs, horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                addLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
