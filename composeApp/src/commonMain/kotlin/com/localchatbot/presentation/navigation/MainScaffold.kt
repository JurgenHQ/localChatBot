package com.localchatbot.presentation.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.di.AppContainer
import com.localchatbot.presentation.components.organisms.AppBottomBar
import com.localchatbot.presentation.components.organisms.BottomTab
import com.localchatbot.presentation.components.organisms.CommandPalette
import com.localchatbot.presentation.components.organisms.CompactContextDialog
import com.localchatbot.presentation.components.organisms.InitProjectDialog
import com.localchatbot.presentation.components.organisms.PaletteCommand
import com.localchatbot.presentation.components.organisms.ToolConfirmationDialog
import com.localchatbot.presentation.features.agent.AgentScreen
import com.localchatbot.presentation.features.agent.AgentViewModel
import com.localchatbot.presentation.features.chat.ChatScreen
import com.localchatbot.presentation.features.chat.ChatViewModel
import com.localchatbot.presentation.features.debug.NetworkInspectorScreen
import com.localchatbot.presentation.features.editor.EditorScreen
import com.localchatbot.presentation.features.editor.EditorViewModel
import com.localchatbot.presentation.features.metrics.SessionMetricsScreen
import com.localchatbot.presentation.features.sessions.SessionDrawer
import com.localchatbot.presentation.features.sessions.SessionsViewModel
import com.localchatbot.presentation.features.models.ModelPickerSheet
import com.localchatbot.presentation.features.models.ModelPickerViewModel
import com.localchatbot.presentation.features.settings.SettingsEditorViewModel
import com.localchatbot.presentation.features.settings.SettingsScreen
import com.localchatbot.presentation.features.settings.SettingsViewModel
import com.localchatbot.presentation.features.mcp.McpServersScreen
import com.localchatbot.presentation.features.mcp.McpServersViewModel
import com.localchatbot.presentation.features.remote.RemoteViewerScreen
import com.localchatbot.presentation.features.remote.RemoteViewerViewModel
import com.localchatbot.presentation.features.skills.SkillsScreen
import com.localchatbot.presentation.features.skills.SkillsViewModel
import com.localchatbot.presentation.features.tasks.TasksScreen
import com.localchatbot.presentation.features.tasks.TasksViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun MainScaffold(container: AppContainer) {
    var selected by rememberSaveable { mutableStateOf(BottomTab.Chat) }
    var modelPickerOpen by rememberSaveable { mutableStateOf(false) }
    var inspectorOpen by rememberSaveable { mutableStateOf(false) }
    var skillsOpen by rememberSaveable { mutableStateOf(false) }
    var mcpOpen by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var remoteViewerOpen by rememberSaveable { mutableStateOf(false) }
    var tasksOpen by rememberSaveable { mutableStateOf(false) }
    var metricsOpen by rememberSaveable { mutableStateOf(false) }
    // En layout ancho el panel de sesiones es permanente pero colapsable: el
    // botón de menú del top bar lo muestra/oculta para dar más ancho al chat.
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }

    val chatViewModel = remember {
        ChatViewModel(
            chatRepository = container.chatRepository,
            preferences = container.preferencesRepository,
            projectRepository = container.projectRepository,
            activeSessionStore = container.activeSessionStore,
            streamingStateStore = container.streamingStateStore,
            pendingUserPromptStore = container.pendingUserPromptStore,
            queuedMessageStore = container.queuedMessageStore,
            applicationScope = container.applicationScope,
            backgroundExecutor = container.backgroundExecutor,
            createSessionUseCase = container.createSession,
            sendMessageUseCase = container.sendMessage,
            modelRepository = container.modelRepository,
            imageSaver = container.imageSaver,
            textToSpeech = container.textToSpeech,
            systemNotifier = container.systemNotifier,
            checkpointStore = container.checkpointStore,
            compactContextUseCase = container.compactContext,
            initProjectUseCase = container.initProject
        )
    }
    val sessionsViewModel = remember {
        SessionsViewModel(
            chatRepository = container.chatRepository,
            preferences = container.preferencesRepository,
            activeSessionStore = container.activeSessionStore,
            createSessionUseCase = container.createSession,
            projectRepository = container.projectRepository,
            checkpointStore = container.checkpointStore,
            queuedMessageStore = container.queuedMessageStore
        )
    }
    val settingsViewModel = remember {
        SettingsViewModel(
            preferences = container.preferencesRepository,
            chats = container.chatRepository,
            checkConnection = container.checkConnection,
            remoteAccessServer = container.remoteAccessServer,
            projects = container.projectRepository
        )
    }
    val skillsViewModel = remember {
        SkillsViewModel(preferences = container.preferencesRepository, skillFileStore = container.skillFileStore)
    }
    val mcpServersViewModel = remember {
        McpServersViewModel(preferences = container.preferencesRepository, mcpToolProvider = container.mcpToolProvider)
    }
    val agentViewModel = remember {
        AgentViewModel(preferences = container.preferencesRepository)
    }
    val editorViewModel = remember {
        EditorViewModel(activeWorkspaceStore = container.activeWorkspaceStore, agent = container.filesystemAgent)
    }
    val remoteViewerViewModel = remember {
        RemoteViewerViewModel(preferences = container.preferencesRepository)
    }
    val tasksViewModel = remember {
        TasksViewModel(
            preferences = container.preferencesRepository,
            scheduler = container.automationScheduler
        )
    }

    val drawerState by sessionsViewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // El agente queda suspendido hasta que se resuelvan; se observan aquí (no dentro de
    // ChatScreen) para que sigan siendo visibles/alcanzables desde cualquier pestaña.
    val pendingConfirmation by container.toolConfirmationController.pending
        .collectAsStateWithLifecycle()
    val pendingUserPrompt by chatViewModel.pendingUserPrompt.collectAsStateWithLifecycle()

    // El panel de `ask_user` vive sobre el composer del chat: si la pregunta llega
    // mientras estás en Agente o Ajustes, vuelve a Chat para que se vea.
    LaunchedEffect(pendingUserPrompt?.question) {
        if (pendingUserPrompt != null) selected = BottomTab.Chat
    }

    // Solo el booleano, no el ChatUiState entero: durante el streaming ese estado cambia
    // cada 120 ms y colectarlo acá recompondría la raíz (y recrearía el modifier de atajos)
    // a ese ritmo. Lo único que hace falta a este nivel es si Esc debe cortar el stream.
    val chatSending by remember(chatViewModel) {
        chatViewModel.state.map { it.sending }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val compactState by chatViewModel.compactState.collectAsStateWithLifecycle()
    val initProjectState by chatViewModel.initProjectState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    /** True si hay algún overlay abierto; lo usa Esc para cerrar de a uno. */
    fun closeTopOverlay(): Boolean {
        when {
            paletteOpen -> paletteOpen = false
            modelPickerOpen -> modelPickerOpen = false
            tasksOpen -> tasksOpen = false
            remoteViewerOpen -> remoteViewerOpen = false
            editorOpen -> editorOpen = false
            mcpOpen -> mcpOpen = false
            skillsOpen -> skillsOpen = false
            inspectorOpen -> inspectorOpen = false
            metricsOpen -> metricsOpen = false
            drawerState.drawerOpen -> sessionsViewModel.closeDrawer()
            else -> return false
        }
        return true
    }

    // Atajos globales. Van en un onPreviewKeyEvent de la raíz: el recorrido de preview baja
    // desde la raíz hasta el elemento con foco, así que llegan aunque el cursor esté en el
    // composer (que se queda con Enter y Shift+Enter para sí).
    val shortcuts = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val cmd = event.isCtrlPressed || event.isMetaPressed
        when {
            cmd && event.key == Key.K -> { paletteOpen = true; true }
            cmd && event.key == Key.N -> {
                selected = BottomTab.Chat
                chatViewModel.newSession()
                true
            }
            cmd && event.key == Key.Comma -> { selected = BottomTab.Settings; true }
            event.key == Key.Escape -> {
                // Esc cierra lo que esté encima; si no hay nada, corta el stream. En ese
                // orden: con un diálogo abierto, Esc significa "cerrá esto", no "pará el modelo".
                if (closeTopOverlay()) true
                else if (chatSending) { chatViewModel.stop(); true }
                else false
            }
            else -> false
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(shortcuts)
    ) {
        // En ventanas anchas (escritorio, tablet apaisada) el drawer de sesiones se
        // muestra permanente como panel lateral; en pantallas angostas vuelve a ser modal.
        val permanentDrawer = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = permanentDrawer && !sidebarCollapsed,
                enter = expandHorizontally(),
                exit = shrinkHorizontally()
            ) {
                Row {
                    SessionDrawer(
                        viewModel = sessionsViewModel,
                        onNewSession = { selected = BottomTab.Chat },
                        onOpenTasks = { tasksOpen = true },
                        showScrim = false
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }

            // imePadding al Column externo: con windowSoftInputMode=adjustResize en el manifest,
            // el sistema redimensiona la ventana al tamaño correcto y imePadding no duplica.
            Column(modifier = Modifier.weight(1f).fillMaxSize().imePadding()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (selected) {
                        BottomTab.Chat -> ChatScreen(
                            chatViewModel = chatViewModel,
                            voiceController = container.voiceController,
                            todoTool = container.todoTool,
                            // Con panel permanente el botón de menú colapsa/expande
                            // el sidebar; en angosto abre el drawer modal.
                            onOpenDrawer = {
                                if (permanentDrawer) sidebarCollapsed = !sidebarCollapsed
                                else sessionsViewModel.openDrawer()
                            },
                            onChangeModel = { modelPickerOpen = true },
                            onOpenEditor = { editorOpen = true },
                            // Click en una referencia a archivo del chat → abre el editor
                            // en ese archivo. Solo desktop (el editor es desktop-only).
                            onOpenFileInEditor = if (PlatformCapabilities.isDesktop) {
                                { path, line ->
                                    editorViewModel.openFile(path, line)
                                    editorOpen = true
                                }
                            } else null,
                            onOpenMetrics = { metricsOpen = true },
                            showMenuButton = true
                        )
                        BottomTab.Agent -> AgentScreen(
                            viewModel = agentViewModel,
                            onOpenSkills = { skillsOpen = true },
                            onOpenMcpServers = { mcpOpen = true },
                            onOpenEditor = { editorOpen = true }
                        )
                        BottomTab.Settings -> SettingsScreen(
                            viewModel = settingsViewModel,
                            editorViewModelFactory = { editor ->
                                SettingsEditorViewModel(
                                    preferences = container.preferencesRepository,
                                    editor = editor,
                                    listModels = container.listModels
                                )
                            },
                            onOpenNetworkInspector = { inspectorOpen = true },
                            onOpenRemoteViewer = { remoteViewerOpen = true }
                        )
                    }
                }
                if (!imeVisible) {
                    AppBottomBar(
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.navigationBarsPadding()
                    )
                }
            }
        }

        if (!permanentDrawer && drawerState.drawerOpen) {
            SessionDrawer(
                viewModel = sessionsViewModel,
                onNewSession = { selected = BottomTab.Chat },
                onOpenTasks = { tasksOpen = true }
            )
        }

        if (inspectorOpen) {
            NetworkInspectorScreen(
                inspector = container.networkInspector,
                onClose = { inspectorOpen = false }
            )
        }

        if (metricsOpen) {
            // Sin ViewModel propio: reutiliza la sesión activa que ya colecta ChatViewModel
            // (viene de `ChatRepository.sessionWithMessages`, con `metrics`/`toolName` cargados).
            val chatState by chatViewModel.state.collectAsStateWithLifecycle()
            SessionMetricsScreen(
                session = chatState.activeSession,
                onClose = { metricsOpen = false }
            )
        }

        if (skillsOpen) {
            SkillsScreen(
                viewModel = skillsViewModel,
                onClose = { skillsOpen = false }
            )
        }

        if (mcpOpen) {
            McpServersScreen(
                viewModel = mcpServersViewModel,
                onClose = { mcpOpen = false }
            )
        }

        if (editorOpen) {
            EditorScreen(
                viewModel = editorViewModel,
                onClose = { editorOpen = false }
            )
        }

        if (remoteViewerOpen) {
            RemoteViewerScreen(
                viewModel = remoteViewerViewModel,
                onClose = { remoteViewerOpen = false }
            )
        }

        if (tasksOpen) {
            TasksScreen(
                viewModel = tasksViewModel,
                onClose = { tasksOpen = false }
            )
        }

        if (modelPickerOpen) {
            val modelPickerVm = remember(modelPickerOpen) {
                ModelPickerViewModel(
                    preferences = container.preferencesRepository,
                    modelRepository = container.modelRepository,
                    chatRepository = container.chatRepository,
                    activeSessionStore = container.activeSessionStore,
                    streamingStateStore = container.streamingStateStore
                )
            }
            ModelPickerSheet(
                viewModel = modelPickerVm,
                onDismiss = {
                    modelPickerOpen = false
                    // Al cerrar el selector puede haber cambiado si el modelo está cargado
                    // sin que cambie el modelo *configurado* (cargar el que ya estaba
                    // seleccionado, o descargarlo). Ese caso no lo ve el colector reactivo
                    // del ChatViewModel, así que se le pide explícitamente.
                    chatViewModel.refreshModelStatus()
                }
            )
        }

        if (paletteOpen) {
            // El estado completo del chat se colecta solo mientras la paleta está abierta
            // (hace falta para saber si hay conversación que exportar).
            val chatState by chatViewModel.state.collectAsStateWithLifecycle()
            // Las conversaciones salen del mismo estado que pinta el drawer, ya filtrado por
            // plataforma (en móvil viene todo en `ungrouped`), así que no hay que releer la BD.
            val sessions = drawerState.ungrouped +
                drawerState.groups.flatMap { it.sessions } +
                drawerState.branchSessions +
                drawerState.subAgentSessions +
                drawerState.automationSessions
            val hasChat = chatState.activeSession?.messages?.isNotEmpty() == true
            val commands = buildList {
                add(PaletteCommand("new", "Nueva conversación", "Ctrl+N", "Acciones") {
                    selected = BottomTab.Chat
                    chatViewModel.newSession()
                })
                if (hasChat) {
                    add(PaletteCommand("compact", "Compactar contexto", "/compact", "Acciones") {
                        selected = BottomTab.Chat
                        chatViewModel.requestCompact()
                    })
                    add(PaletteCommand("export-copy", "Exportar conversación (copiar Markdown)", null, "Acciones") {
                        chatViewModel.activeSessionMarkdown()?.let {
                            clipboard.setText(AnnotatedString(it))
                            chatViewModel.notifyCopied("Conversación")
                        }
                    })
                    if (PlatformCapabilities.isDesktop) {
                        add(PaletteCommand("export-file", "Exportar conversación (guardar .md)", null, "Acciones") {
                            chatViewModel.exportActiveSessionToFile()
                        })
                    }
                    add(PaletteCommand("metrics", "Ver métricas de la sesión", null, "Acciones") {
                        metricsOpen = true
                    })
                }
                if (chatState.sending) {
                    add(PaletteCommand("stop", "Detener generación", "Esc", "Acciones") { chatViewModel.stop() })
                }
                if (PlatformCapabilities.isDesktop) {
                    add(PaletteCommand("mode", "Alternar modo Plan/Build", null, "Acciones") {
                        chatViewModel.toggleAgentMode()
                    })
                }
                add(PaletteCommand("t-chat", "Ir a Chat", null, "Navegación") { selected = BottomTab.Chat })
                add(PaletteCommand("t-agent", "Ir a Agente", null, "Navegación") { selected = BottomTab.Agent })
                add(PaletteCommand("t-settings", "Ir a Ajustes", "Ctrl+,", "Navegación") { selected = BottomTab.Settings })
                add(PaletteCommand("o-skills", "Abrir Skills", null, "Navegación") { skillsOpen = true })
                add(PaletteCommand("o-mcp", "Abrir servidores MCP", null, "Navegación") { mcpOpen = true })
                add(PaletteCommand("o-inspector", "Abrir inspector de red", null, "Navegación") { inspectorOpen = true })
                if (PlatformCapabilities.isDesktop) {
                    add(PaletteCommand("o-editor", "Abrir editor", null, "Navegación") { editorOpen = true })
                    add(PaletteCommand("o-tasks", "Abrir tareas programadas", null, "Navegación") { tasksOpen = true })
                }
                add(PaletteCommand("o-model", "Cambiar modelo", null, "Navegación") { modelPickerOpen = true })
                sessions.forEach { s ->
                    add(PaletteCommand("s-${s.id}", s.title, null, "Conversaciones") {
                        sessionsViewModel.selectSession(s.id)
                        selected = BottomTab.Chat
                    })
                }
            }
            CommandPalette(commands = commands, onDismiss = { paletteOpen = false })
        }

        compactState?.let { compact ->
            CompactContextDialog(
                state = compact,
                onSummaryChange = chatViewModel::onCompactSummaryChange,
                onApply = chatViewModel::applyCompact,
                onDismiss = chatViewModel::dismissCompact
            )
        }

        initProjectState?.let { init ->
            InitProjectDialog(
                state = init,
                onContentChange = chatViewModel::onInitProjectContentChange,
                onApply = chatViewModel::applyInitProject,
                onDismiss = chatViewModel::dismissInitProject
            )
        }

        // Último: el diálogo de aprobación se dibuja por encima de cualquier overlay y,
        // al vivir fuera del `when (selected)`, aparece aunque no estés en la pestaña
        // Chat (antes el turno se quedaba bloqueado sin nada visible en pantalla).
        pendingConfirmation?.let { pending ->
            ToolConfirmationDialog(
                pending = pending,
                onApprove = { container.toolConfirmationController.resolve(pending.id, approved = true) },
                onReject = { container.toolConfirmationController.resolve(pending.id, approved = false) }
            )
        }
    }
}
