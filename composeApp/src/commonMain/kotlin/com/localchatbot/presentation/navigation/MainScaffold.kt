package com.localchatbot.presentation.navigation

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
import com.localchatbot.di.AppContainer
import com.localchatbot.presentation.components.organisms.AppBottomBar
import com.localchatbot.presentation.components.organisms.BottomTab
import com.localchatbot.presentation.features.chat.ChatScreen
import com.localchatbot.presentation.features.chat.ChatViewModel
import com.localchatbot.presentation.features.debug.NetworkInspectorScreen
import com.localchatbot.presentation.features.sessions.SessionDrawer
import com.localchatbot.presentation.features.sessions.SessionsViewModel
import com.localchatbot.presentation.features.settings.SettingsEditor
import com.localchatbot.presentation.features.settings.SettingsEditorSheet
import com.localchatbot.presentation.features.settings.SettingsEditorViewModel
import com.localchatbot.presentation.features.settings.SettingsScreen
import com.localchatbot.presentation.features.settings.SettingsViewModel

@Composable
fun MainScaffold(container: AppContainer) {
    var selected by rememberSaveable { mutableStateOf(BottomTab.Chat) }
    var modelPickerOpen by rememberSaveable { mutableStateOf(false) }
    var inspectorOpen by rememberSaveable { mutableStateOf(false) }

    val chatViewModel = remember {
        ChatViewModel(
            chatRepository = container.chatRepository,
            preferences = container.preferencesRepository,
            activeSessionStore = container.activeSessionStore,
            streamingStateStore = container.streamingStateStore,
            applicationScope = container.applicationScope,
            backgroundExecutor = container.backgroundExecutor,
            createSessionUseCase = container.createSession,
            sendMessageUseCase = container.sendMessage,
            modelRepository = container.modelRepository,
            imageSaver = container.imageSaver
        )
    }
    val sessionsViewModel = remember {
        SessionsViewModel(
            chatRepository = container.chatRepository,
            preferences = container.preferencesRepository,
            activeSessionStore = container.activeSessionStore,
            createSessionUseCase = container.createSession
        )
    }
    val settingsViewModel = remember {
        SettingsViewModel(
            preferences = container.preferencesRepository,
            chats = container.chatRepository,
            checkConnection = container.checkConnection
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
            if (permanentDrawer) {
                SessionDrawer(
                    viewModel = sessionsViewModel,
                    onOpenSettings = { selected = BottomTab.Settings },
                    onNewSession = { selected = BottomTab.Chat },
                    showScrim = false
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
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
                            onOpenDrawer = sessionsViewModel::openDrawer,
                            onChangeModel = { modelPickerOpen = true },
                            showMenuButton = !permanentDrawer
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
                            onOpenNetworkInspector = { inspectorOpen = true }
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
                onOpenSettings = { selected = BottomTab.Settings },
                onNewSession = { selected = BottomTab.Chat }
            )
        }

        if (inspectorOpen) {
            NetworkInspectorScreen(
                inspector = container.networkInspector,
                onClose = { inspectorOpen = false }
            )
        }

        if (modelPickerOpen) {
            val modelEditorVm = remember(modelPickerOpen) {
                SettingsEditorViewModel(
                    preferences = container.preferencesRepository,
                    editor = SettingsEditor.Model,
                    listModels = container.listModels
                )
            }
            SettingsEditorSheet(
                viewModel = modelEditorVm,
                onDismiss = { modelPickerOpen = false }
            )
        }
    }
}
