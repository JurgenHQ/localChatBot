package com.localchatbot.presentation.features.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.image.rememberImagePicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.core.voice.VoiceConversationController
import com.localchatbot.core.voice.VoiceMode
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.tools.TodoItem
import com.localchatbot.domain.tools.TodoTool
import com.localchatbot.presentation.components.molecules.AgentControlsBar
import com.localchatbot.presentation.components.molecules.ContextUsageBar
import com.localchatbot.presentation.components.molecules.TodoProgressPanel
import com.localchatbot.presentation.components.organisms.ChatComposer
import com.localchatbot.presentation.components.organisms.ChatMessageList
import com.localchatbot.presentation.components.organisms.ChatSearchBar
import com.localchatbot.presentation.components.organisms.ChatTopBar
import com.localchatbot.presentation.components.organisms.ErrorBanner
import com.localchatbot.presentation.components.organisms.ToolConfirmationDialog
import com.localchatbot.presentation.features.templates.PromptTemplatesSheet
import com.localchatbot.presentation.features.voice.VoiceConversationSheet
import com.localchatbot.presentation.preview.PreviewData
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Fallback estático del empty state. Se muestra mientras el modelo aún no ha
 * respondido por primera vez (no hay sugerencias dinámicas en cache). Tras la
 * primera respuesta exitosa, [ChatViewModel.refreshSuggestions] reemplaza esta
 * lista con 3 generadas por el modelo: una de desarrollo, una de noticias
 * actuales y una random.
 */
private val DEFAULT_EMPTY_STATE_SUGGESTIONS = listOf(
    "Explícame el patrón Repository",
    "Revisa este snippet de Kotlin",
    "Resume un texto largo"
)

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    voiceController: VoiceConversationController,
    toolConfirmationController: ToolConfirmationController,
    todoTool: TodoTool,
    onOpenDrawer: () -> Unit,
    onChangeModel: () -> Unit = {},
    showMenuButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by chatViewModel.state.collectAsStateWithLifecycle()
    val voiceMode by voiceController.mode.collectAsStateWithLifecycle()
    val pendingConfirmation by toolConfirmationController.pending.collectAsStateWithLifecycle()
    val allTodos by todoTool.state.collectAsStateWithLifecycle()
    val activeSessionId = state.activeSession?.id
    val todoItems = remember(allTodos, activeSessionId) {
        if (activeSessionId == null) emptyList() else allTodos[activeSessionId].orEmpty()
    }
    val imagePicker = rememberImagePicker(onResult = chatViewModel::onImagePicked)
    // Picker de directorio para los chips del agente. En móvil es no-op,
    // pero como la barra solo se renderiza cuando isDesktop, da igual.
    val workspacePicker = rememberDirectoryPicker(onResult = chatViewModel::updateFsWorkspaceDir)
    var templatesOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        ChatContent(
            state = state,
            todoItems = todoItems,
            onClearTodos = {
                val sid = activeSessionId ?: return@ChatContent
                scope.launch { todoTool.clearSession(sid) }
            },
            onOpenDrawer = onOpenDrawer,
            onNewSession = chatViewModel::newSession,
            onDraftChange = chatViewModel::onDraftChange,
            onSend = chatViewModel::send,
            onAttach = { imagePicker.launch() },
            onRemoveAttachment = chatViewModel::clearAttachment,
            onVoice = voiceController::start,
            onResendMessage = chatViewModel::resendMessage,
            onEditMessage = chatViewModel::editMessage,
            onChangeModel = onChangeModel,
            onStop = chatViewModel::stop,
            onRegenerate = chatViewModel::regenerateLastResponse,
            onOpenTemplates = { templatesOpen = true },
            onSaveImage = chatViewModel::saveImage,
            onPasteImage = chatViewModel::onImagePicked,
            onDismissError = chatViewModel::dismissError,
            voiceSupported = PlatformCapabilities.voiceSupported,
            showMenuButton = showMenuButton,
            showAgentBar = PlatformCapabilities.isDesktop,
            onPickWorkspace = { workspacePicker.launch() },
            onToggleSandbox = chatViewModel::toggleFsSandbox,
            onToggleYolo = chatViewModel::toggleFsYoloMode
        )
        if (voiceMode != VoiceMode.Off) {
            VoiceConversationSheet(
                mode = voiceMode,
                onClose = voiceController::stop,
                onSubmit = voiceController::submitNow
            )
        }
        if (templatesOpen) {
            PromptTemplatesSheet(
                templates = state.promptTemplates,
                onPick = { template ->
                    chatViewModel.onDraftChange(
                        if (state.draft.isBlank()) template.body
                        else state.draft.trimEnd() + "\n\n" + template.body
                    )
                    templatesOpen = false
                },
                onSave = chatViewModel::savePromptTemplates,
                onDismiss = { templatesOpen = false }
            )
        }
        pendingConfirmation?.let { pending ->
            ToolConfirmationDialog(
                pending = pending,
                onApprove = { toolConfirmationController.resolve(pending.id, approved = true) },
                onReject = { toolConfirmationController.resolve(pending.id, approved = false) }
            )
        }
    }
}

@Composable
fun ChatContent(
    state: ChatUiState,
    todoItems: List<TodoItem> = emptyList(),
    onClearTodos: () -> Unit = {},
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit = {},
    onVoice: () -> Unit = {},
    onResendMessage: (String) -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onChangeModel: () -> Unit = {},
    onStop: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
    onSaveImage: (ByteArray) -> Unit = {},
    onPasteImage: ((ByteArray) -> Unit)? = null,
    onDismissError: () -> Unit = {},
    voiceSupported: Boolean = true,
    showMenuButton: Boolean = true,
    showAgentBar: Boolean = false,
    onPickWorkspace: () -> Unit = {},
    onToggleSandbox: () -> Unit = {},
    onToggleYolo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current

    val messages = state.activeSession?.messages
    // Anclar el último mensaje del usuario arriba para que el assistant aparezca llenando hacia abajo.
    val lastUserMessageIndex = messages?.indexOfLast { it.role == Role.User } ?: -1
    val lastUserMessageId = if (lastUserMessageIndex >= 0) messages?.get(lastUserMessageIndex)?.id else null
    LaunchedEffect(lastUserMessageId) {
        if (lastUserMessageIndex >= 0) {
            // +1 por el DayHeader que ocupa la posición 0 en la LazyColumn.
            listState.animateScrollToItem(index = lastUserMessageIndex + 1, scrollOffset = 0)
        }
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        ChatTopBar(
            title = state.activeSession?.title ?: "Nueva conversación",
            subtitle = state.modelName.ifBlank { "Sin modelo" },
            onMenuClick = onOpenDrawer,
            onNewClick = onNewSession,
            onSubtitleClick = onChangeModel,
            onSearchClick = if (state.activeSession?.messages?.isNotEmpty() == true) {
                {
                    searchOpen = !searchOpen
                    if (!searchOpen) { searchQuery = ""; currentMatchIndex = 0 }
                }
            } else null,
            showMenuButton = showMenuButton
        )

        // Índices ABSOLUTOS dentro de active.messages que contienen la query.
        // Los usamos tanto para el contador "N/M" como para hacer scroll.
        val matchIndices: List<Int> = remember(searchOpen, searchQuery, state.activeSession?.id, state.activeSession?.messages?.size) {
            if (!searchOpen || searchQuery.isBlank()) emptyList()
            else state.activeSession?.messages
                ?.mapIndexedNotNull { idx, m ->
                    if (m.content.contains(searchQuery, ignoreCase = true)) idx else null
                }
                .orEmpty()
        }
        // Si la query cambia, volver al primer match.
        LaunchedEffect(searchQuery) { currentMatchIndex = 0 }
        // Scroll automático cuando cambia el match actual.
        LaunchedEffect(currentMatchIndex, matchIndices) {
            val msgIdx = matchIndices.getOrNull(currentMatchIndex) ?: return@LaunchedEffect
            // +1 por el DayHeader en posición 0 de la LazyColumn.
            listState.animateScrollToItem(msgIdx + 1)
        }

        if (searchOpen) {
            ChatSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = matchIndices.size,
                currentDisplayIndex = if (matchIndices.isEmpty()) 0 else currentMatchIndex + 1,
                onPrev = {
                    if (matchIndices.isNotEmpty()) {
                        currentMatchIndex = (currentMatchIndex - 1 + matchIndices.size) % matchIndices.size
                    }
                },
                onNext = {
                    if (matchIndices.isNotEmpty()) {
                        currentMatchIndex = (currentMatchIndex + 1) % matchIndices.size
                    }
                },
                onClose = {
                    searchQuery = ""
                    currentMatchIndex = 0
                    searchOpen = false
                }
            )
        }

        if (state.activeSession != null && state.tokensUsed > 0 && !searchOpen) {
            ContextUsageBar(tokensUsed = state.tokensUsed, tokensMax = state.tokensMax)
        }
        TodoProgressPanel(items = todoItems, onClearTodos = onClearTodos)

        val dismissKeyboardModifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { keyboard?.hide() })
        }

        val active = state.activeSession
        if (active == null || active.messages.isEmpty()) {
            ChatEmptyState(
                suggestions = state.dynamicSuggestions ?: DEFAULT_EMPTY_STATE_SUGGESTIONS,
                onSuggestion = onDraftChange,
                modifier = Modifier.weight(1f).then(dismissKeyboardModifier)
            )
        } else {
            ChatMessageList(
                session = active,
                listState = listState,
                sending = state.sending,
                toolActivity = state.toolActivity,
                highlightQuery = if (searchOpen) searchQuery.takeIf { it.isNotBlank() } else null,
                currentMatchAbsIndex = matchIndices.getOrNull(currentMatchIndex) ?: -1,
                onResendMessage = onResendMessage,
                onEditMessage = onEditMessage,
                onRegenerate = onRegenerate,
                onSaveImage = onSaveImage,
                onTapMessage = { keyboard?.hide() },
                modifier = Modifier.weight(1f).fillMaxWidth().then(dismissKeyboardModifier)
            )
        }

        state.errorMessage?.let { msg ->
            ErrorBanner(
                message = msg,
                onDismiss = onDismissError,
                onCopy = { clipboard.setText(AnnotatedString(msg)) }
            )
        }

        ChatComposer(
            value = state.draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            onAttach = onAttach,
            sending = state.sending,
            attachedImageBytes = state.attachedImageBytes,
            onRemoveAttachment = onRemoveAttachment,
            onVoice = onVoice,
            onStop = onStop,
            onTemplates = onOpenTemplates,
            voiceSupported = voiceSupported,
            onPasteImage = onPasteImage,
            agentBar = if (showAgentBar) {
                {
                    AgentControlsBar(
                        workspaceDir = state.fsWorkspaceDir,
                        // El chip de "Sandbox" muestra ON cuando los paths están
                        // restringidos al workspace (allowOutside == false).
                        sandboxOn = !state.fsAllowOutsideWorkspace,
                        yoloOn = state.fsYoloMode,
                        onPickWorkspace = onPickWorkspace,
                        onToggleSandbox = onToggleSandbox,
                        onToggleYolo = onToggleYolo
                    )
                }
            } else null
        )
        Spacer(Modifier.height(Spacing.xs))
    }
}

@Preview
@Composable
private fun ChatEmptyPreview() = PreviewSurface {
    ChatContent(
        state = ChatUiState(
            activeSession = PreviewData.emptySession,
            modelName = "llama-3.1-8b-instruct"
        ),
        onOpenDrawer = {}, onNewSession = {}, onDraftChange = {}, onSend = {}, onAttach = {}
    )
}

@Preview
@Composable
private fun ChatWithMessagesPreview() = PreviewSurface {
    ChatContent(
        state = ChatUiState(
            activeSession = PreviewData.activeSession,
            modelName = "llama-3.1-8b-instruct"
        ),
        onOpenDrawer = {}, onNewSession = {}, onDraftChange = {}, onSend = {}, onAttach = {}
    )
}

@Preview
@Composable
private fun ChatSendingPreview() = PreviewSurface {
    ChatContent(
        state = ChatUiState(
            activeSession = PreviewData.activeSession,
            modelName = "llama-3.1-8b-instruct",
            draft = "",
            sending = true
        ),
        onOpenDrawer = {}, onNewSession = {}, onDraftChange = {}, onSend = {}, onAttach = {}
    )
}

@Preview
@Composable
private fun ChatErrorPreview() = PreviewSurface {
    ChatContent(
        state = ChatUiState(
            activeSession = PreviewData.activeSession,
            modelName = "llama-3.1-8b-instruct",
            errorMessage = "HTTP 503: Service Unavailable"
        ),
        onOpenDrawer = {}, onNewSession = {}, onDraftChange = {}, onSend = {}, onAttach = {}
    )
}

@Preview
@Composable
private fun ChatWithMessagesDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    ChatContent(
        state = ChatUiState(
            activeSession = PreviewData.activeSession,
            modelName = "llama-3.1-8b-instruct"
        ),
        onOpenDrawer = {}, onNewSession = {}, onDraftChange = {}, onSend = {}, onAttach = {}
    )
}
