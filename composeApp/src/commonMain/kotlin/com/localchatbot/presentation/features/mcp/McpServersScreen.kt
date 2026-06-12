package com.localchatbot.presentation.features.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.presentation.components.atoms.SectionLabel
import com.localchatbot.presentation.components.atoms.StatusDot
import com.localchatbot.presentation.components.molecules.SectionCard

@Composable
fun McpServersScreen(
    viewModel: McpServersViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        McpServersContent(
            state = state,
            onClose = onClose,
            onAdd = viewModel::openAddSheet,
            onEdit = viewModel::openEditSheet,
            onDelete = viewModel::deleteServer,
            onToggle = viewModel::toggleServer,
            onTest = viewModel::testConnection
        )

        if (state.showEditSheet) {
            McpServerEditSheet(
                editing = state.editingServer,
                onDismiss = viewModel::closeSheet,
                onSave = viewModel::saveServer
            )
        }
    }
}

@Composable
private fun McpServersContent(
    state: McpServersUiState,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit
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
                "Servidores MCP",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Agregar servidor",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        if (state.servers.isEmpty()) {
            Text(
                "Sin servidores configurados. Agrega un servidor MCP para que el modelo acceda a tools externas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SectionLabel("Servidores")
            SectionCard {
                state.servers.forEachIndexed { index, item ->
                    McpServerRow(
                        item = item,
                        onEdit = { onEdit(item.config) },
                        onDelete = { onDelete(item.config.id) },
                        onToggle = { onToggle(item.config.id, it) },
                        onTest = { onTest(item.config.id) }
                    )
                    if (index < state.servers.lastIndex) HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        Text(
            "Los servidores MCP exponen tools externas (bases de datos, APIs, herramientas) que el modelo " +
                "puede invocar vía un endpoint HTTP remoto. Usa los headers para autenticación si el server lo requiere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun McpServerRow(
    item: McpServerUiItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit
) {
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
            StatusIndicator(item.status, modifier = Modifier.padding(end = Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.config.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    item.config.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.status == McpServerStatus.Connected && item.toolCount > 0) {
                    Text(
                        "${item.toolCount} tool${if (item.toolCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (item.status == McpServerStatus.Error && item.errorMessage != null) {
                    Text(
                        item.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Switch(checked = item.config.enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onTest) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Probar conexión",
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

@Composable
private fun StatusIndicator(status: McpServerStatus, modifier: Modifier = Modifier) {
    when (status) {
        McpServerStatus.Connecting ->
            CircularProgressIndicator(modifier = modifier.size(8.dp), strokeWidth = 1.5.dp)
        McpServerStatus.Connected ->
            StatusDot(color = Color(0xFF4CAF50), modifier = modifier)
        McpServerStatus.Error ->
            StatusDot(color = MaterialTheme.colorScheme.error, modifier = modifier)
        McpServerStatus.Unknown ->
            StatusDot(color = MaterialTheme.colorScheme.outlineVariant, modifier = modifier)
    }
}
