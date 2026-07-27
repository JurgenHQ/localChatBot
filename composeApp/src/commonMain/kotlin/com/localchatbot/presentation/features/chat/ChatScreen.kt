package com.localchatbot.presentation.features.chat

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.localchatbot.core.fs.rememberFilePicker
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.image.rememberImagePicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.platform.revealInFileManager
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
import com.localchatbot.presentation.components.molecules.ToolCallLogChip
import com.localchatbot.presentation.components.organisms.ChatComposer
import com.localchatbot.presentation.components.organisms.ChatMessageList
import com.localchatbot.presentation.components.organisms.ChatSearchBar
import com.localchatbot.presentation.components.organisms.ChatTopBar
import com.localchatbot.presentation.components.organisms.ErrorBanner
import com.localchatbot.presentation.components.organisms.TopBarMenuItem
import com.localchatbot.presentation.features.templates.PromptTemplatesSheet
import com.localchatbot.presentation.features.voice.VoiceConversationSheet
import com.localchatbot.presentation.preview.PreviewData
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    voiceController: VoiceConversationController,
    todoTool: TodoTool,
    onOpenDrawer: () -> Unit,
    onChangeModel: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onOpenFileInEditor: ((String, Int?) -> Unit)? = null,
    onOpenMetrics: () -> Unit = {},
    showMenuButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by chatViewModel.state.collectAsStateWithLifecycle()
    val speakingMessageId by chatViewModel.speakingMessageId.collectAsStateWithLifecycle()
    val voiceMode by voiceController.mode.collectAsStateWithLifecycle()
    val pendingUserPrompt by chatViewModel.pendingUserPrompt.collectAsStateWithLifecycle()
    val pendingRevert by chatViewModel.pendingRevert.collectAsStateWithLifecycle()
    val pendingScrollMessageId by chatViewModel.pendingScrollMessageId.collectAsStateWithLifecycle()
    val allTodos by todoTool.state.collectAsStateWithLifecycle()
    val activeSessionId = state.activeSession?.id
    val todoItems = remember(allTodos, activeSessionId) {
        if (activeSessionId == null) emptyList() else allTodos[activeSessionId].orEmpty()
    }
    val imagePicker = rememberImagePicker(onResult = chatViewModel::onImagePicked)
    // Picker de directorio para los chips del agente. En móvil es no-op,
    // pero como la barra solo se renderiza cuando isDesktop, da igual.
    val workspacePicker = rememberDirectoryPicker(onResult = chatViewModel::updateFsWorkspaceDir)
    val filePicker = rememberFilePicker(
        onResult = chatViewModel::attachTextFile,
        onError = chatViewModel::attachTextFileError
    )
    var templatesOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Para las acciones de exportar del menú "⋮" (copiar al portapapeles).
    val clipboard = LocalClipboardManager.current

    // El VM no alcanza el portapapeles (solo existe dentro de Compose): pide, y acá se cumple.
    val clipboardRequest by chatViewModel.clipboardRequest.collectAsStateWithLifecycle()
    LaunchedEffect(clipboardRequest) {
        val text = clipboardRequest ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(text))
        chatViewModel.consumeClipboardRequest()
        chatViewModel.notifyCopied("Conversación")
    }

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
            attachedTextFiles = state.attachedTextFiles,
            onAttachTextFile = { filePicker.launch() },
            onRemoveTextFile = chatViewModel::removeTextFile,
            onVoice = voiceController::start,
            onResendMessage = chatViewModel::resendMessage,
            onEditMessage = chatViewModel::editMessage,
            onChangeModel = onChangeModel,
            onOpenEditor = onOpenEditor,
            onStop = chatViewModel::stop,
            onRegenerate = chatViewModel::regenerateLastResponse,
            speakingMessageId = speakingMessageId,
            onSpeakMessage = chatViewModel::speakMessage,
            onStopSpeak = chatViewModel::stopSpeaking,
            onOpenTemplates = { templatesOpen = true },
            onSaveImage = chatViewModel::saveImage,
            onPasteImage = chatViewModel::onImagePicked,
            onDismissError = chatViewModel::dismissError,
            voiceSupported = PlatformCapabilities.voiceSupported,
            showMenuButton = showMenuButton,
            showAgentBar = PlatformCapabilities.isDesktop,
            onPickWorkspace = { workspacePicker.launch() },
            onOpenWorkspaceFolder = { state.fsWorkspaceDir?.let(::revealInFileManager) },
            onToggleSandbox = chatViewModel::toggleFsSandbox,
            onToggleYolo = chatViewModel::toggleFsYoloMode,
            onTogglePreviewEdits = chatViewModel::toggleFsPreviewEdits,
            onToggleMode = chatViewModel::toggleAgentMode,
            onSelectSkill = chatViewModel::selectSkill,
            onClearSkill = chatViewModel::clearPendingSkill,
            pendingPrompt = pendingUserPrompt,
            onSelectPromptOption = chatViewModel::submitPromptOption,
            onOpenFileInEditor = onOpenFileInEditor,
            onRevertTurn = chatViewModel::requestRevert,
            onRemoveQueued = chatViewModel::removeQueued,
            onSendQueuedNow = { chatViewModel.sendQueuedNow() },
            onCopyTurn = { messageId ->
                chatViewModel.turnMarkdown(messageId)?.let {
                    clipboard.setText(AnnotatedString(it))
                    chatViewModel.notifyCopied("Turno")
                }
            },
            slashCommands = chatViewModel.availableSlashCommands(),
            onSelectCommand = chatViewModel::runSlashCommand,
            pendingScrollMessageId = pendingScrollMessageId,
            onPendingScrollConsumed = chatViewModel::consumePendingScroll,
            topBarMenuItems = buildList {
                if (state.activeSession?.messages?.isNotEmpty() == true) {
                    add(
                        TopBarMenuItem("Copiar conversación (Markdown)") {
                            chatViewModel.activeSessionMarkdown()?.let {
                                clipboard.setText(AnnotatedString(it))
                                chatViewModel.notifyCopied("Conversación")
                            }
                        }
                    )
                    // Guardar a archivo solo en desktop: en móvil `saveTextFile` es no-op.
                    if (PlatformCapabilities.isDesktop) {
                        add(TopBarMenuItem("Guardar conversación (.md)", chatViewModel::exportActiveSessionToFile))
                    }
                    add(TopBarMenuItem("Compactar contexto", chatViewModel::requestCompact))
                    if (state.contextCompacted) {
                        add(TopBarMenuItem("Deshacer compactación", chatViewModel::undoCompact))
                    }
                    add(TopBarMenuItem("Ver métricas de la sesión", onOpenMetrics))
                }
            }
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
        pendingRevert?.let { revert ->
            RevertTurnDialog(
                files = revert.files,
                onConfirm = chatViewModel::confirmRevert,
                onDismiss = chatViewModel::dismissRevert
            )
        }
    }
}

/**
 * Confirmación del "revertir este turno": lista los archivos que se restaurarían
 * a su estado previo al turno. Los mensajes del chat se conservan.
 */
@Composable
private fun RevertTurnDialog(
    files: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revertir cambios de este turno") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Estos archivos volverán a su estado previo al turno " +
                        "(lo creado se elimina, lo editado o borrado se restaura):",
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    files.forEach { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    "Cambios posteriores sobre estos archivos también se perderán. " +
                        "Lo que tocaron run_command, MCP o scripts solo se revierte si el " +
                        "workspace es un repo git, y únicamente en archivos que git ya sigue: " +
                        "los que se hayan creado sin añadir al índice no se borran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Revertir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
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
    attachedTextFiles: List<com.localchatbot.core.fs.AttachedTextFile> = emptyList(),
    onAttachTextFile: (() -> Unit)? = null,
    onRemoveTextFile: ((String) -> Unit)? = null,
    onVoice: () -> Unit = {},
    onResendMessage: (String) -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onChangeModel: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onStop: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    speakingMessageId: String? = null,
    onSpeakMessage: (String, String) -> Unit = { _, _ -> },
    onStopSpeak: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
    onSaveImage: (ByteArray) -> Unit = {},
    onPasteImage: ((ByteArray) -> Unit)? = null,
    onDismissError: () -> Unit = {},
    voiceSupported: Boolean = true,
    showMenuButton: Boolean = true,
    showAgentBar: Boolean = false,
    onPickWorkspace: () -> Unit = {},
    onOpenWorkspaceFolder: () -> Unit = {},
    onToggleSandbox: () -> Unit = {},
    onToggleYolo: () -> Unit = {},
    onTogglePreviewEdits: () -> Unit = {},
    onToggleMode: () -> Unit = {},
    onSelectSkill: (com.localchatbot.domain.model.SkillDefinition) -> Unit = {},
    onClearSkill: () -> Unit = {},
    pendingPrompt: com.localchatbot.core.state.PendingUserPrompt? = null,
    onSelectPromptOption: (String) -> Unit = {},
    onOpenFileInEditor: ((String, Int?) -> Unit)? = null,
    onRevertTurn: ((String) -> Unit)? = null,
    onRemoveQueued: (String) -> Unit = {},
    onSendQueuedNow: () -> Unit = {},
    /** Acciones del menú "⋮" de la barra (exportar, compactar). */
    topBarMenuItems: List<TopBarMenuItem> = emptyList(),
    /** Copiar un turno suelto al portapapeles, desde la burbuja del usuario. */
    onCopyTurn: ((String) -> Unit)? = null,
    /** Comandos `/` ofrecibles ahora, para el popup del composer. */
    slashCommands: List<SlashCommand> = emptyList(),
    onSelectCommand: (SlashCommand) -> Unit = {},
    /** Mensaje al que saltar, pedido por la búsqueda global del drawer. */
    pendingScrollMessageId: String? = null,
    onPendingScrollConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current

    val messages = state.activeSession?.messages
    // Con reverseLayout = true el índice 0 es siempre el fondo (mensaje más reciente).
    val messageCount = messages?.size ?: 0
    // Solo auto-scrollear si el usuario ya estaba pegado al fondo (o si el mensaje
    // nuevo es suyo — acaba de enviar). Si subió a leer mientras el modelo escribe,
    // no arrastrarlo hacia abajo con cada mensaje nuevo de la ronda de tools.
    val nearBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    LaunchedEffect(messageCount) {
        if (messageCount == 0) return@LaunchedEffect
        // Con un salto pendiente desde la búsqueda, el fondo no es el destino: al abrir la
        // conversación este efecto y el del salto se disparan a la vez, y sin esto ganaría
        // el que llegue último.
        if (pendingScrollMessageId != null) return@LaunchedEffect
        val lastIsUser = messages?.lastOrNull()?.role == Role.User
        if (lastIsUser || nearBottom) listState.animateScrollToItem(0)
    }

    // El scroll al mensaje buscado lo hace ChatMessageList, que es quien sabe qué mensajes
    // renderiza de verdad (filtra los que no pintan nada) y cuántos items van por delante.
    // Aquí solo se guarda a cuál se saltó, para marcarlo un momento al llegar.
    var jumpHighlightId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jumpHighlightId) {
        if (jumpHighlightId == null) return@LaunchedEffect
        // La marca es para orientar al aterrizar, no un estado permanente: en una
        // conversación larga, sin esto no sabés cuál de las burbujas es la que buscabas.
        delay(JUMP_HIGHLIGHT_MS)
        jumpHighlightId = null
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        ChatTopBar(
            title = state.activeSession?.title ?: "Nueva conversación",
            subtitle = when {
                state.modelName.isBlank() -> "Sin modelo"
                state.modelLoaded == false -> "Sin modelo cargado"
                else -> state.modelName
            },
            onMenuClick = onOpenDrawer,
            onNewClick = onNewSession,
            onSubtitleClick = onChangeModel,
            onSearchClick = if (state.activeSession?.messages?.isNotEmpty() == true) {
                {
                    searchOpen = !searchOpen
                    if (!searchOpen) { searchQuery = ""; currentMatchIndex = 0 }
                }
            } else null,
            onEditorClick = if (showAgentBar && state.fsWorkspaceDir != null) onOpenEditor else null,
            menuItems = topBarMenuItems,
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
        // Con reverseLayout: mensaje en posición msgIdx del original
        // → posición invertida = (total - 1 - msgIdx) en la LazyColumn.
        LaunchedEffect(currentMatchIndex, matchIndices) {
            val msgIdx = matchIndices.getOrNull(currentMatchIndex) ?: return@LaunchedEffect
            val total = messages?.size ?: return@LaunchedEffect
            listState.animateScrollToItem(total - 1 - msgIdx)
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
            ContextUsageBar(
                tokensUsed = state.tokensUsed,
                tokensMax = state.tokensMax,
                compacted = state.contextCompacted
            )
        }
        TodoProgressPanel(items = todoItems, onClearTodos = onClearTodos)

        val dismissKeyboardModifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { keyboard?.hide() })
        }

        val active = state.activeSession
        if (active == null || active.messages.isEmpty()) {
            ChatEmptyState(
                modifier = Modifier.weight(1f).then(dismissKeyboardModifier)
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                    speakingMessageId = speakingMessageId,
                    onSpeakMessage = onSpeakMessage,
                    onStopSpeak = onStopSpeak,
                    onSaveImage = onSaveImage,
                    onTapMessage = { keyboard?.hide() },
                    onOpenFileInEditor = onOpenFileInEditor,
                    onRevertTurn = onRevertTurn,
                    queuedMessages = state.queuedMessages,
                    onRemoveQueued = onRemoveQueued,
                    onCopyTurn = onCopyTurn,
                    // "Enviar ahora" solo cuando no hay turno en curso: con el modelo
                    // trabajando la cola se vaciará sola al terminar.
                    onSendQueuedNow = if (!state.sending) onSendQueuedNow else null,
                    scrollToMessageId = pendingScrollMessageId,
                    onScrolledToMessage = { id ->
                        jumpHighlightId = id
                        onPendingScrollConsumed()
                    },
                    highlightedMessageId = jumpHighlightId,
                    modifier = Modifier.fillMaxSize().then(dismissKeyboardModifier)
                )
                // Botón flotante para volver al fondo cuando el usuario subió a leer.
                val showJumpToBottom by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 0 }
                }
                // Llamada calificada: dentro de un Box anidado en Column, la
                // resolución implícita elige la extensión de ColumnScope y falla.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showJumpToBottom,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md)
                ) {
                    ScrollToBottomButton(
                        onClick = { scrollScope.launch { listState.animateScrollToItem(0) } }
                    )
                }
            }
        }

        // Indicador de progreso del agente (N tool calls + log) en una barra fija sobre el
        // composer, fuera de la lista de mensajes, para no afectar el espaciado entre burbujas.
        if (state.sending && state.toolCallLog.isNotEmpty()) {
            ToolCallLogChip(
                toolCallLog = state.toolCallLog,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
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
            attachedTextFiles = attachedTextFiles,
            onAttachTextFile = onAttachTextFile,
            onRemoveTextFile = onRemoveTextFile,
            onVoice = onVoice,
            onStop = onStop,
            onTemplates = onOpenTemplates,
            voiceSupported = voiceSupported,
            onPasteImage = onPasteImage,
            pendingSkill = state.pendingSkill,
            installedSkills = state.installedEnabledSkills,
            onSelectSkill = onSelectSkill,
            slashCommands = slashCommands,
            onSelectCommand = onSelectCommand,
            onClearSkill = onClearSkill,
            pendingPrompt = pendingPrompt,
            onSelectPromptOption = onSelectPromptOption,
            agentBar = if (showAgentBar) {
                {
                    AgentControlsBar(
                        workspaceDir = state.fsWorkspaceDir,
                        gitBranch = state.gitBranch,
                        // El chip de "Sandbox" muestra ON cuando los paths están
                        // restringidos al workspace (allowOutside == false).
                        sandboxOn = !state.fsAllowOutsideWorkspace,
                        yoloOn = state.fsYoloMode,
                        previewEditsOn = state.fsPreviewEdits,
                        planMode = state.planMode,
                        onPickWorkspace = onPickWorkspace,
                        onOpenWorkspaceFolder = onOpenWorkspaceFolder,
                        onToggleSandbox = onToggleSandbox,
                        onToggleYolo = onToggleYolo,
                        onTogglePreviewEdits = onTogglePreviewEdits,
                        onToggleMode = onToggleMode
                    )
                }
            } else null
        )
        Spacer(Modifier.height(Spacing.xs))
    }
}

@Composable
private fun ScrollToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Ir al final",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
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

/** Cuánto dura la marca del mensaje al que se salta desde la búsqueda global. */
private const val JUMP_HIGHLIGHT_MS = 2500L
