package com.localchatbot.presentation.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionMode
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.presentation.components.atoms.SectionLabel
import com.localchatbot.presentation.components.atoms.StatusDot
import com.localchatbot.presentation.components.molecules.SectionCard
import com.localchatbot.presentation.components.molecules.SettingsRow
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    editorViewModelFactory: (SettingsEditor) -> SettingsEditorViewModel,
    onOpenNetworkInspector: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        SettingsContent(
            preferences = state.preferences,
            status = state.status,
            onOpenEditor = viewModel::open,
            onConnectionModeChange = viewModel::onConnectionModeChange,
            onRetryConnection = viewModel::retryConnection,
            onClearHistory = viewModel::clearHistory,
            onOpenNetworkInspector = onOpenNetworkInspector,
            onOpenSkills = onOpenSkills,
            onOpenMcpServers = onOpenMcpServers,
            onPickWorkspace = viewModel::updateFsWorkspaceDir,
            onClearWorkspace = { viewModel.updateFsWorkspaceDir(null) },
            onToggleYolo = viewModel::toggleFsYoloMode,
            onToggleAllowOutside = viewModel::toggleFsAllowOutsideWorkspace
        )

        state.openEditor?.let { editor ->
            val editorVm = remember(editor) { editorViewModelFactory(editor) }
            SettingsEditorSheet(
                viewModel = editorVm,
                onDismiss = viewModel::closeEditor
            )
        }
    }
}

@Composable
fun SettingsContent(
    preferences: AppPreferences,
    status: ConnectionStatus,
    onOpenEditor: (SettingsEditor) -> Unit,
    onConnectionModeChange: (ConnectionMode) -> Unit = {},
    onRetryConnection: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenNetworkInspector: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    onPickWorkspace: (String) -> Unit = {},
    onClearWorkspace: () -> Unit = {},
    onToggleYolo: (Boolean) -> Unit = {},
    onToggleAllowOutside: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cfg = preferences.connection

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Text(
            "Configuración",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        SectionLabel("Conexión")
        // Toggle de modo: Red local vs URL directa (tunnel)
        ConnectionModeToggle(
            selected = cfg.mode,
            onSelect = onConnectionModeChange
        )
        SectionCard {
            if (cfg.mode == ConnectionMode.LocalNetwork) {
                SettingsRow(
                    title = "Dirección IP",
                    onClick = { onOpenEditor(SettingsEditor.Ip) },
                    trailing = { MonoValue(cfg.ip.ifBlank { "—" }) }
                )
                Divider()
                SettingsRow(
                    title = "Puerto",
                    onClick = { onOpenEditor(SettingsEditor.Port) },
                    trailing = { MonoValue(cfg.port.ifBlank { "—" }) }
                )
            } else {
                SettingsRow(
                    title = "URL del servidor",
                    onClick = { onOpenEditor(SettingsEditor.DirectUrl) },
                    trailing = {
                        MonoValue(
                            cfg.directUrl.ifBlank { "—" }
                                .removePrefix("https://").removePrefix("http://"),
                            maxChars = 22
                        )
                    }
                )
            }
            Divider()
            SettingsRow(
                title = "Modelo",
                onClick = { onOpenEditor(SettingsEditor.Model) },
                trailing = { MonoValue(cfg.model.ifBlank { "—" }, maxChars = 18) }
            )
            Divider()
            SettingsRow(
                title = "Estado",
                onClick = onRetryConnection,
                trailing = { StatusTrailing(status) }
            )
        }
        Text(
            when (cfg.mode) {
                ConnectionMode.LocalNetwork -> "Endpoint compatible con OpenAI en tu red local."
                ConnectionMode.DirectUrl    -> "Endpoint accesible desde internet (Cloudflare Tunnel, ngrok, etc.)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Apariencia")
        SectionCard {
            SettingsRow(
                title = "Tema",
                onClick = { onOpenEditor(SettingsEditor.Theme) },
                trailing = {
                    PillValue(
                        when (preferences.themeMode) {
                            ThemeMode.System -> "Automático"
                            ThemeMode.Light -> "Claro"
                            ThemeMode.Dark -> "Oscuro"
                        }
                    )
                }
            )
            Divider()
            SettingsRow(
                title = "Acento",
                onClick = { onOpenEditor(SettingsEditor.Accent) },
                trailing = {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (preferences.accentSeed != 0L) Color(preferences.accentSeed)
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                }
            )
        }

        SectionLabel("Comportamiento")
        SectionCard {
            SettingsRow(
                title = "System prompt",
                onClick = { onOpenEditor(SettingsEditor.SystemPrompt) },
                trailing = {
                    MonoValue(
                        preferences.defaultSystemPrompt.ifBlank { "Sin configurar" },
                        maxChars = 18
                    )
                }
            )
        }
        Text(
            "Instrucción inicial enviada como mensaje 'system' en cada conversación.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Imágenes y diagramas (opcional)")
        SectionCard {
            SettingsRow(
                title = "URL del servicio",
                onClick = { onOpenEditor(SettingsEditor.ImageServiceUrl) },
                trailing = {
                    MonoValue(
                        preferences.imageServiceUrl.ifBlank {
                            when {
                                cfg.mode == ConnectionMode.LocalNetwork && cfg.ip.isNotBlank() ->
                                    "auto: ${cfg.ip}:8080"
                                else -> "Sin configurar"
                            }
                        },
                        maxChars = 22
                    )
                }
            )
        }
        Text(
            when (cfg.mode) {
                ConnectionMode.LocalNetwork ->
                    "FastAPI con /generate-image y /render-diagram. Si lo dejas vacío se usa la IP de LM Studio en el puerto 8080."
                ConnectionMode.DirectUrl ->
                    "FastAPI con /generate-image y /render-diagram. En modo URL directa debes configurar aquí el tunnel del servicio de imágenes (puede ser distinto al de LM Studio)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Búsqueda web (opcional)")
        SectionCard {
            SettingsRow(
                title = "Tavily API key",
                onClick = { onOpenEditor(SettingsEditor.TavilyApiKey) },
                trailing = {
                    if (preferences.tavilyApiKey.isBlank()) {
                        MonoValue("Sin configurar", maxChars = 16)
                    } else {
                        MonoValue(preferences.tavilyApiKey.maskKey(), maxChars = 14)
                    }
                }
            )
        }
        Text(
            buildString {
                if (preferences.webSearchEnabled) {
                    append("Búsqueda web activa. ")
                } else {
                    append("Sin API key, el modelo responde solo con su conocimiento interno. ")
                }
                append("Obtén una key gratis en https://app.tavily.com")
            },
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
            "Conecta servidores MCP externos (stdio o HTTP) para que el modelo acceda a tools de terceros: bases de datos, APIs, herramientas de desarrollo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Desarrollador")
        SectionCard {
            SettingsRow(
                title = "Inspector de red",
                onClick = onOpenNetworkInspector,
                trailing = { MonoValue("Ver →", maxChars = 6) }
            )
        }
        Text(
            "Examina el JSON crudo de cada llamada al modelo: request, response, duración y errores.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Datos")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClearHistory)
                .padding(Spacing.lg)
        ) {
            Text(
                "Borrar todo el historial",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ConnectionModeToggle(
    selected: ConnectionMode,
    onSelect: (ConnectionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ConnectionMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (mode) {
                        ConnectionMode.LocalNetwork -> "Red local"
                        ConnectionMode.DirectUrl    -> "URL directa"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun String.maskKey(): String {
    if (isBlank()) return "—"
    val visible = takeLast(4)
    return "••••$visible"
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

@Composable
private fun PillValue(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatusTrailing(status: ConnectionStatus) {
    val (label, color) = when (status) {
        is ConnectionStatus.Connected -> "Conectado" to Color(0xFF2EBD66)
        ConnectionStatus.Checking -> "Verificando" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionStatus.Error -> "Desconectado" to MaterialTheme.colorScheme.error
        ConnectionStatus.Unknown -> "Sin verificar" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        StatusDot(color = color)
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

private val SamplePrefs = AppPreferences(
    connection = ConnectionConfig(ip = "192.168.1.42", port = "1234", model = "llama-3.1-8b-instruct"),
    themeMode = ThemeMode.System,
    accentSeed = 0L,
    onboardingDone = true
)

private val SamplePrefsUrl = AppPreferences(
    connection = ConnectionConfig(
        mode = ConnectionMode.DirectUrl,
        model = "llama-3.1-8b-instruct",
        directUrl = "https://abc.trycloudflare.com"
    ),
    themeMode = ThemeMode.System,
    accentSeed = 0L,
    onboardingDone = true
)

@Preview
@Composable
private fun SettingsConnectedPreview() = PreviewSurface {
    SettingsContent(
        preferences = SamplePrefs,
        status = ConnectionStatus.Connected(latencyMs = 42),
        onOpenEditor = {}, onRetryConnection = {}, onClearHistory = {}
    )
}

@Preview
@Composable
private fun SettingsDirectUrlPreview() = PreviewSurface {
    SettingsContent(
        preferences = SamplePrefsUrl,
        status = ConnectionStatus.Connected(latencyMs = 80),
        onOpenEditor = {}, onRetryConnection = {}, onClearHistory = {}
    )
}

@Preview
@Composable
private fun SettingsDisconnectedPreview() = PreviewSurface {
    SettingsContent(
        preferences = SamplePrefs,
        status = ConnectionStatus.Error("Sin conexión"),
        onOpenEditor = {}, onRetryConnection = {}, onClearHistory = {}
    )
}

@Preview
@Composable
private fun SettingsEmptyPreview() = PreviewSurface {
    SettingsContent(
        preferences = AppPreferences.Default,
        status = ConnectionStatus.Unknown,
        onOpenEditor = {}, onRetryConnection = {}, onClearHistory = {}
    )
}

@Preview
@Composable
private fun SettingsDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    SettingsContent(
        preferences = SamplePrefs.copy(themeMode = ThemeMode.Dark),
        status = ConnectionStatus.Connected(latencyMs = 42),
        onOpenEditor = {}, onRetryConnection = {}, onClearHistory = {}
    )
}
