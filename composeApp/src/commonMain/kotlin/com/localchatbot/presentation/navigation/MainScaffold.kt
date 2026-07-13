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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.di.AppContainer
import com.localchatbot.presentation.components.organisms.AppBottomBar
import com.localchatbot.presentation.components.organisms.BottomTab
import com.localchatbot.presentation.features.agent.AgentScreen
import com.localchatbot.presentation.features.agent.AgentViewModel
import com.localchatbot.presentation.features.chat.ChatScreen
import com.localchatbot.presentation.features.chat.ChatViewModel
import com.localchatbot.presentation.features.debug.NetworkInspectorScreen
import com.localchatbot.presentation.features.editor.EditorScreen
import com.localchatbot.presentation.features.editor.EditorViewModel
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
    // En layout ancho el panel de sesiones es permanente pero colapsable: el
    // botón de menú del top bar lo muestra/oculta para dar más ancho al chat.
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }

    val chatViewModel = remember {
        ChatViewModel(
            chatRepository = container.chatRepository,
            preferences = container.preferencesRepository,
            projectRepository = container.projectRepository,
            activeSessionStore = container.activeSessionStore,
            streamingStateStore = container.streamingStateStore,
            pendingUserPromptStore = container.pendingUserPromptStore,
            applicationScope = container.applicationScope,
            backgroundExecutor = container.backgroundExecutor,
            createSessionUseCase = container.createSession,
            sendMessageUseCase = container.sendMessage,
            modelRepository = container.modelRepository,
            imageSaver = container.imageSaver,
            textToSpeech = container.textToSpeech,
            systemNotifier = container.systemNotifier,
            checkpointStore = container.checkpointStore
        )
    }
    val sessionsViewModel = remember {
        SessionsViewModel(
            chatRepository = container.chatRepository,
            preferences = container.preferencesRepository,
            activeSessionStore = container.activeSessionStore,
            createSessionUseCase = container.createSession,
            projectRepository = container.projectRepository,
            checkpointStore = container.checkpointStore
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
        EditorViewModel(preferences = container.preferencesRepository, agent = container.filesystemAgent)
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                            toolConfirmationController = container.toolConfirmationController,
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
                onDismiss = { modelPickerOpen = false }
            )
        }
    }
}
