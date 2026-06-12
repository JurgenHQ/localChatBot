package com.localchatbot.presentation.features.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.presentation.components.atoms.SectionLabel
import com.localchatbot.presentation.components.molecules.SectionCard
import com.localchatbot.presentation.components.molecules.SettingsRow
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onOpenSkills: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val preferences by viewModel.state.collectAsStateWithLifecycle()
    AgentContent(
        preferences = preferences,
        onOpenSkills = onOpenSkills,
        onOpenMcpServers = onOpenMcpServers,
        onPickWorkspace = viewModel::updateFsWorkspaceDir,
        onClearWorkspace = { viewModel.updateFsWorkspaceDir(null) },
        onToggleYolo = viewModel::toggleFsYoloMode,
        onToggleAllowOutside = viewModel::toggleFsAllowOutsideWorkspace,
        modifier = modifier
    )
}

@Composable
fun AgentContent(
    preferences: AppPreferences,
    onOpenSkills: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    onPickWorkspace: (String) -> Unit = {},
    onClearWorkspace: () -> Unit = {},
    onToggleYolo: (Boolean) -> Unit = {},
    onToggleAllowOutside: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Text(
            "Agente",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Configura las capacidades del agente: skills, herramientas externas y acceso a tu sistema.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Skills")
        SectionCard {
            SettingsRow(
                title = "Skills instalados",
                onClick = onOpenSkills,
                trailing = {
                    val activeCount = preferences.installedSkills.count { it.enabled }
                    MonoValue(
                        if (activeCount == 0) "Ninguno activo" else "$activeCount activo${if (activeCount != 1) "s" else ""}",
                        maxChars = 16
                    )
                }
            )
        }
        Text(
            "Amplía el comportamiento del agente con instrucciones especializadas. El modelo los carga bajo demanda.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("MCP (Model Context Protocol)")
        SectionCard {
            SettingsRow(
                title = "Servidores MCP",
                onClick = onOpenMcpServers,
                trailing = {
                    val count = preferences.mcpServers.count { it.enabled }
                    MonoValue(
                        if (count == 0) "Sin configurar" else "$count activo${if (count != 1) "s" else ""}",
                        maxChars = 16
                    )
                }
            )
        }
        Text(
            "Conecta servidores MCP externos (HTTP) para que el modelo acceda a tools de terceros: bases de datos, APIs, herramientas de desarrollo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (PlatformCapabilities.isDesktop) {
            FilesystemSection(
                preferences = preferences,
                onPickWorkspace = onPickWorkspace,
                onClearWorkspace = onClearWorkspace,
                onToggleYolo = onToggleYolo,
                onToggleAllowOutside = onToggleAllowOutside
            )
        }
    }
}

@Composable
private fun FilesystemSection(
    preferences: AppPreferences,
    onPickWorkspace: (String) -> Unit,
    onClearWorkspace: () -> Unit,
    onToggleYolo: (Boolean) -> Unit,
    onToggleAllowOutside: (Boolean) -> Unit
) {
    val picker = rememberDirectoryPicker(onResult = onPickWorkspace)

    SectionLabel("Acceso al sistema de archivos (desktop)")
    SectionCard {
        SettingsRow(
            title = "Workspace",
            onClick = { picker.launch() },
            trailing = {
                MonoValue(
                    preferences.fsWorkspaceDir ?: "Sin configurar",
                    maxChars = 22
                )
            }
        )
        if (preferences.fsWorkspaceDir != null) {
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClearWorkspace)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Quitar workspace",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Divider()
        SwitchRow(
            title = "Omitir confirmaciones (modo YOLO)",
            checked = preferences.fsYoloMode,
            onCheckedChange = onToggleYolo
        )
        Divider()
        SwitchRow(
            title = "Permitir acceso fuera del workspace",
            checked = preferences.fsAllowOutsideWorkspace,
            onCheckedChange = onToggleAllowOutside
        )
    }
    Text(
        buildString {
            append("Habilita las tools de filesystem y shell para que el modelo cree archivos, ")
            append("lea contenido y ejecute comandos. Cada acción pide aprobación a menos que ")
            append("actives YOLO. La opción \"fuera del workspace\" permite paths absolutos y ")
            append("rutas que escapan del directorio configurado — peligroso, úsalo con cuidado.")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp
    )
}

@Composable
private fun MonoValue(text: String, maxChars: Int = 24) {
    val display = if (text.length > maxChars) text.take(maxChars - 1) + "…" else text
    Text(
        display,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Preview
@Composable
private fun AgentContentPreview() {
    PreviewSurface {
        AgentContent(preferences = AppPreferences.Default)
    }
}
