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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionProfile
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.presentation.components.atoms.SectionLabel
import com.localchatbot.presentation.components.atoms.StatusDot
import com.localchatbot.presentation.components.molecules.SectionCard
import com.localchatbot.presentation.components.molecules.SettingsRow
import com.localchatbot.presentation.preview.PreviewSurface
import com.localchatbot.core.storage.rememberSettingsExporter
import com.localchatbot.core.storage.rememberSettingsImporter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    editorViewModelFactory: (SettingsEditor) -> SettingsEditorViewModel,
    onOpenNetworkInspector: () -> Unit = {},
    onOpenRemoteViewer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val exporter = rememberSettingsExporter(onError = viewModel::fileError)
    val importer = rememberSettingsImporter(
        onResult = viewModel::onImportFileSelected,
        onError = viewModel::fileError
    )

    // El ViewModel construye el JSON y lo emite; aquí abrimos el diálogo "guardar como".
    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { json -> exporter(json) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsContent(
            preferences = state.preferences,
            status = state.status,
            onOpenEditor = viewModel::open,
            onRetryConnection = viewModel::retryConnection,
            onActivateProfile = viewModel::activateProfile,
            onAddProfile = viewModel::addProfile,
            onDeleteProfile = viewModel::deleteProfile,
            onRenameProfile = viewModel::renameProfile,
            onClearHistory = viewModel::clearHistory,
            onToggleHttps = viewModel::toggleHttps,
            onOpenNetworkInspector = onOpenNetworkInspector,
            onExportSettings = viewModel::exportSettings,
            onImportSettings = importer,
            onOpenRemoteViewer = onOpenRemoteViewer,
            remoteClients = state.remoteClients,
            localIps = state.localIps,
            onToggleRemoteAccess = viewModel::toggleRemoteAccess,
            onRegenerateRemotePin = viewModel::regenerateRemotePin,
            onToggleDesktopNotifications = viewModel::toggleDesktopNotifications
        )

        state.openEditor?.let { editor ->
            val editorVm = remember(editor) { editorViewModelFactory(editor) }
            SettingsEditorSheet(
                viewModel = editorVm,
                onDismiss = viewModel::closeEditor
            )
        }

        if (state.pendingImportJson != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissImport,
                title = { Text("Importar configuración") },
                text = {
                    Text(
                        "Esto reemplazará TODA tu configuración actual (servidor, API keys, " +
                            "skills, servidores MCP, etc.) por la del archivo. Esta acción no se puede deshacer."
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmImport) { Text("Reemplazar") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissImport) { Text("Cancelar") }
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun SettingsContent(
    preferences: AppPreferences,
    status: ConnectionStatus,
    onOpenEditor: (SettingsEditor) -> Unit,
    onRetryConnection: () -> Unit,
    onActivateProfile: (String) -> Unit = {},
    onAddProfile: () -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onRenameProfile: (String, String) -> Unit = { _, _ -> },
    onClearHistory: () -> Unit,
    onToggleHttps: (Boolean) -> Unit = {},
    onOpenNetworkInspector: () -> Unit = {},
    onExportSettings: () -> Unit = {},
    onImportSettings: () -> Unit = {},
    onOpenRemoteViewer: () -> Unit = {},
    remoteClients: Int = 0,
    localIps: List<String> = emptyList(),
    onToggleRemoteAccess: (Boolean) -> Unit = {},
    onRegenerateRemotePin: () -> Unit = {},
    onToggleDesktopNotifications: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cfg = preferences.connection
    var renamingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }

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

        SectionLabel("Perfiles de conexión")
        SectionCard {
            preferences.connectionProfiles.forEachIndexed { idx, profile ->
                SettingsRow(
                    title = profile.name,
                    onClick = { onActivateProfile(profile.id) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.id == preferences.activeConnectionProfileId) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Activo",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { renamingProfile = profile }) {
                                Icon(Icons.Default.Edit, contentDescription = "Renombrar perfil")
                            }
                            if (preferences.connectionProfiles.size > 1) {
                                IconButton(onClick = { onDeleteProfile(profile.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Borrar perfil")
                                }
                            }
                        }
                    }
                )
                if (idx < preferences.connectionProfiles.lastIndex) Divider()
            }
            if (preferences.connectionProfiles.size < 3) {
                Divider()
                SettingsRow(title = "+ Añadir perfil", onClick = onAddProfile, trailing = {})
            }
        }

        SectionLabel("Servidor")
        SectionCard {
            SettingsRow(
                title = "Host / IP",
                onClick = { onOpenEditor(SettingsEditor.Ip) },
                trailing = { MonoValue(cfg.ip.ifBlank { "—" }, maxChars = 22) }
            )
            Divider()
            SettingsRow(
                title = "Puerto",
                onClick = { onOpenEditor(SettingsEditor.Port) },
                trailing = { MonoValue(cfg.port.ifBlank { "—" }) }
            )
            Divider()
            SettingsRow(
                title = "HTTPS",
                onClick = { onToggleHttps(!cfg.useHttps) },
                trailing = {
                    Switch(checked = cfg.useHttps, onCheckedChange = onToggleHttps)
                }
            )
            Divider()
            SettingsRow(
                title = "Modelo",
                onClick = { onOpenEditor(SettingsEditor.Model) },
                trailing = { MonoValue(cfg.model.ifBlank { "—" }, maxChars = 18) }
            )
            Divider()
            SettingsRow(
                title = "API key",
                onClick = { onOpenEditor(SettingsEditor.ApiKey) },
                trailing = {
                    MonoValue(
                        if (cfg.apiKey.isBlank()) "Sin configurar" else cfg.apiKey.maskKey(),
                        maxChars = 14
                    )
                }
            )
            Divider()
            SettingsRow(
                title = "Estado",
                onClick = onRetryConnection,
                trailing = { StatusTrailing(status) }
            )
        }
        Text(
            "Endpoint compatible con OpenAI: LM Studio, Ollama o llama.cpp (en tu red o por VPN), " +
                "un túnel (Cloudflare, ngrok) o un proveedor cloud (OpenAI, DeepSeek, Groq, OpenRouter…). " +
                "Activa HTTPS para túneles y cloud; usa la API key si el endpoint la requiere. " +
                "Con LM Studio mostramos además la longitud de contexto y los modelos cargados.",
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
            if (PlatformCapabilities.isDesktop) {
                Divider()
                SettingsRow(
                    title = "Notificaciones",
                    onClick = {
                        onToggleDesktopNotifications(!preferences.desktopNotificationsEnabled)
                    },
                    trailing = {
                        Switch(
                            checked = preferences.desktopNotificationsEnabled,
                            onCheckedChange = onToggleDesktopNotifications
                        )
                    }
                )
            }
        }
        if (PlatformCapabilities.isDesktop) {
            Text(
                "Muestra un aviso del sistema y rebota el icono del dock al terminar " +
                    "una respuesta o una tarea programada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Instrucción inicial enviada como mensaje 'system' en cada conversación.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Parámetros de generación")
        val gp = preferences.generationParams
        SectionCard {
            SettingsRow(
                title = "Temperatura",
                onClick = { onOpenEditor(SettingsEditor.Temperature) },
                trailing = { MonoValue(gp.temperature?.toString() ?: "por defecto") }
            )
            Divider()
            SettingsRow(
                title = "Top-P",
                onClick = { onOpenEditor(SettingsEditor.TopP) },
                trailing = { MonoValue(gp.topP?.toString() ?: "por defecto") }
            )
            Divider()
            SettingsRow(
                title = "Max tokens",
                onClick = { onOpenEditor(SettingsEditor.MaxTokens) },
                trailing = { MonoValue(gp.maxTokens?.toString() ?: "por defecto") }
            )
            Divider()
            SettingsRow(
                title = "Presence penalty",
                onClick = { onOpenEditor(SettingsEditor.PresencePenalty) },
                trailing = { MonoValue(gp.presencePenalty?.toString() ?: "por defecto") }
            )
            Divider()
            SettingsRow(
                title = "Frequency penalty",
                onClick = { onOpenEditor(SettingsEditor.FrequencyPenalty) },
                trailing = { MonoValue(gp.frequencyPenalty?.toString() ?: "por defecto") }
            )
            Divider()
            SettingsRow(
                title = "Seed",
                onClick = { onOpenEditor(SettingsEditor.Seed) },
                trailing = { MonoValue(gp.seed?.toString() ?: "aleatorio") }
            )
        }
        Text(
            "Se envían en cada request. Vacío = el servidor usa su valor por defecto. " +
                "La temperatura del agente (0.3) se aplica solo cuando no hay un valor configurado aquí.",
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
                            if (cfg.ip.isNotBlank() && !cfg.useHttps) "auto: ${cfg.ip}:8080"
                            else "Sin configurar"
                        },
                        maxChars = 22
                    )
                }
            )
        }
        Text(
            "FastAPI con /generate-image y /render-diagram. Si lo dejas vacío y el servidor es HTTP local, " +
                "se deriva del host en el puerto 8080. Para HTTPS/cloud debes configurarlo a mano.",
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
            SectionLabel("Búsqueda semántica (opcional)")
            SectionCard {
                SettingsRow(
                    title = "Modelo de embeddings",
                    onClick = { onOpenEditor(SettingsEditor.EmbeddingsModel) },
                    trailing = {
                        MonoValue(preferences.embeddingsModel.ifBlank { "Autodetectar" }, maxChars = 22)
                    }
                )
            }
            Text(
                "Lo usa la tool search_code_semantic para indexar el workspace vía /v1/embeddings. " +
                    "Vacío = se usa el primer modelo del servidor cuyo nombre contenga \"embed\". " +
                    "Ojo: en LM Studio el modelo de embeddings ocupa memoria junto al de chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (PlatformCapabilities.isDesktop) {
            val remote = preferences
            SectionLabel("Acceso remoto")
            SectionCard {
                SettingsRow(
                    title = "Activar servidor",
                    onClick = { onToggleRemoteAccess(!remote.remoteAccessEnabled) },
                    trailing = {
                        Switch(
                            checked = remote.remoteAccessEnabled,
                            onCheckedChange = onToggleRemoteAccess
                        )
                    }
                )
                if (remote.remoteAccessEnabled) {
                    Divider()
                    SettingsRow(
                        title = "PIN",
                        onClick = onRegenerateRemotePin,
                        trailing = { MonoValue(remote.remoteAccessPin.ifBlank { "—" }) }
                    )
                    Divider()
                    SettingsRow(
                        title = "Conectados",
                        onClick = {},
                        trailing = { MonoValue(remoteClients.toString()) }
                    )
                    Divider()
                    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                        Text(
                            "Abre desde otro dispositivo en la misma red/VPN:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val urls = localIps.map { "http://$it:${remote.remoteAccessPort}" }
                        if (urls.isEmpty()) {
                            MonoValue("http://<ip-de-este-pc>:${remote.remoteAccessPort}", maxChars = 60)
                        } else {
                            urls.forEach { MonoValue(it, maxChars = 60) }
                        }
                    }
                }
            }
            Text(
                "Revisa y aprueba cambios desde otro dispositivo. Aprobar comandos en remoto es " +
                    "potente: mantenlo sólo en redes/VPN de confianza.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionLabel("Visor remoto")
        SectionCard {
            SettingsRow(
                title = "Abrir visor remoto",
                onClick = onOpenRemoteViewer,
                trailing = { MonoValue("Ver →", maxChars = 6) }
            )
        }
        Text(
            "Conecta con otro equipo que tenga el acceso remoto activo y revisa/aprueba sus " +
                "cambios desde aquí, sin abrir el navegador.",
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

        SectionLabel("Backup")
        SectionCard {
            SettingsRow(
                title = "Exportar configuración",
                onClick = onExportSettings,
                trailing = { MonoValue("Guardar →", maxChars = 12) }
            )
            Divider()
            SettingsRow(
                title = "Importar configuración",
                onClick = onImportSettings,
                trailing = { MonoValue("Abrir →", maxChars = 10) }
            )
        }
        Text(
            "Exporta todas tus configuraciones a un archivo .json para moverlas a otra máquina. " +
                "⚠️ El archivo incluye tus API keys en texto plano: guárdalo en un lugar seguro. " +
                "Importar reemplaza por completo la configuración actual.",
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

    renamingProfile?.let { profile ->
        var value by remember(profile.id) { mutableStateOf(profile.name) }
        AlertDialog(
            onDismissRequest = { renamingProfile = null },
            title = { Text("Renombrar perfil") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = value.isNotBlank(),
                    onClick = {
                        onRenameProfile(profile.id, value)
                        renamingProfile = null
                    }
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { renamingProfile = null }) { Text("Cancelar") }
            }
        )
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
    connectionProfiles = listOf(
        ConnectionProfile(
            id = "p1",
            name = "Perfil 1",
            config = ConnectionConfig(ip = "192.168.1.42", port = "1234", model = "llama-3.1-8b-instruct")
        )
    ),
    activeConnectionProfileId = "p1",
    themeMode = ThemeMode.System,
    accentSeed = 0L,
    onboardingDone = true
)

private val SamplePrefsUrl = AppPreferences(
    connectionProfiles = listOf(
        ConnectionProfile(
            id = "p1",
            name = "Perfil 1",
            config = ConnectionConfig(
                ip = "abc.trycloudflare.com",
                port = "",
                useHttps = true,
                model = "llama-3.1-8b-instruct"
            )
        )
    ),
    activeConnectionProfileId = "p1",
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
private fun SettingsHttpsPreview() = PreviewSurface {
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
