package com.localchatbot.presentation.features.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.confirm.PendingConfirmation
import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.image.rememberImagePicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.core.voice.VoiceConversationController
import com.localchatbot.core.voice.VoiceMode
import com.localchatbot.domain.model.Role
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.atoms.TypingIndicator
import com.localchatbot.presentation.components.molecules.AgentControlsBar
import com.localchatbot.presentation.components.molecules.ContextUsageBar
import com.localchatbot.presentation.components.molecules.MessageBubble
import com.localchatbot.presentation.components.organisms.ChatComposer
import com.localchatbot.presentation.components.organisms.ChatTopBar
import com.localchatbot.presentation.features.templates.PromptTemplatesSheet
import com.localchatbot.presentation.features.voice.VoiceConversationSheet
import com.localchatbot.presentation.preview.PreviewData
import com.localchatbot.presentation.preview.PreviewSurface
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
    onOpenDrawer: () -> Unit,
    onChangeModel: () -> Unit = {},
    showMenuButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by chatViewModel.state.collectAsStateWithLifecycle()
    val voiceMode by voiceController.mode.collectAsStateWithLifecycle()
    val pendingConfirmation by toolConfirmationController.pending.collectAsStateWithLifecycle()
    val imagePicker = rememberImagePicker(onResult = chatViewModel::onImagePicked)
    // Picker de directorio para los chips del agente. En móvil es no-op,
    // pero como la barra solo se renderiza cuando isDesktop, da igual.
    val workspacePicker = rememberDirectoryPicker(onResult = chatViewModel::updateFsWorkspaceDir)
    var templatesOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        ChatContent(
            state = state,
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().then(dismissKeyboardModifier),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                contentPadding = PaddingValues(vertical = Spacing.lg)
            ) {
                item { DayHeader(epochMs = active.createdAtEpochMs) }
                // Mostramos todos los mensajes — la navegación por matches hace
                // scroll a cada uno en lugar de filtrar la lista.
                val visibleMessages = active.messages
                val lastAssistantId = visibleMessages
                    .lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
                    ?.id
                val activeQuery = if (searchOpen) searchQuery.takeIf { it.isNotBlank() } else null
                itemsIndexed(visibleMessages, key = { _, msg -> msg.id }) { msgIdx, msg ->
                    // Leemos currentMatchIndex (State) DENTRO del composable del
                    // ítem → cada ítem se suscribe y recompone al navegar matches.
                    val currentAbsIdx = matchIndices.getOrNull(currentMatchIndex) ?: -1
                    MessageBubble(
                        message = msg,
                        onResend = if (msg.role == Role.User && !state.sending) {
                            { onResendMessage(msg.id) }
                        } else null,
                        onEdit = if (msg.role == Role.User && !state.sending) {
                            { onEditMessage(msg.id) }
                        } else null,
                        onCopy = if (msg.role == Role.Assistant && msg.content.isNotBlank()) {
                            { clipboard.setText(AnnotatedString(msg.content)) }
                        } else null,
                        onRegenerate = if (
                            msg.id == lastAssistantId && !state.sending
                        ) onRegenerate else null,
                        onSaveImage = if (msg.imageDataUrl != null) onSaveImage else null,
                        onTap = { keyboard?.hide() },
                        highlightQuery = activeQuery,
                        isCurrentMatch = msgIdx == currentAbsIdx
                    )
                }
                val lastVisible = active.messages.lastOrNull { it.role != Role.Tool }
                val activity = state.toolActivity
                val showTyping = state.sending && activity == null && (
                    lastVisible == null ||
                    lastVisible.role == Role.User ||
                    (lastVisible.role == Role.Assistant && lastVisible.content.isBlank())
                )
                if (activity != null) {
                    item {
                        ToolActivityRow(label = activity.label, detail = activity.detail)
                    }
                } else if (showTyping) {
                    item { AssistantTypingRow() }
                }
            }
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

@Composable
private fun AssistantTypingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AppLogo(size = 28.dp)
        Box(modifier = Modifier.padding(top = 8.dp)) {
            TypingIndicator()
        }
    }
}

/**
 * Banner compacto para mostrar errores sobre el composer. Diseñado a propósito
 * con altura limitada (1 línea de resumen + acciones) para que un mensaje muy
 * largo — típico de errores HTTP con cuerpo o stack traces — no empuje el
 * composer fuera de la pantalla y bloquee la interacción con el teclado.
 *
 * Si el mensaje no cabe en una línea, aparece "Detalles" para abrir un diálogo
 * scrollable con el texto completo.
 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    var detailsOpen by remember(message) { mutableStateOf(false) }

    val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val isLong = message.length > 120 || message.contains('\n')

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.height(20.dp)
        )
        Text(
            text = firstLine.ifBlank { "Error" },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isLong) {
            TextButton(
                onClick = { detailsOpen = true },
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
            ) {
                Text(
                    "Detalles",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(onClick = onDismiss)
                .padding(Spacing.xs),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cerrar",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.height(18.dp)
            )
        }
    }

    if (detailsOpen) {
        ErrorDetailsDialog(
            message = message,
            onCopy = onCopy,
            onDismiss = { detailsOpen = false }
        )
    }
}

@Composable
private fun ErrorDetailsDialog(
    message: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Detalles del error", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            // Caja con altura máxima para que en errores enormes la tarjeta del
            // diálogo no crezca fuera de pantalla; el contenido scrollea dentro.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(Spacing.md)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = {
            TextButton(onClick = {
                onCopy()
                onDismiss()
            }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.height(16.dp)
                    )
                    Text("Copiar")
                }
            }
        }
    )
}

@Composable
private fun ToolActivityRow(label: String, detail: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AppLogo(size = 28.dp)
        Column(
            modifier = Modifier.weight(1f).padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = "\"${detail}\"",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            TypingIndicator(dotSize = 6.dp)
        }
    }
}

@Composable
private fun DayHeader(epochMs: Long) {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val h = dt.hour.toString().padStart(2, '0')
    val m = dt.minute.toString().padStart(2, '0')
    Text(
        text = "HOY  ·  $h:$m",
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ToolConfirmationDialog(
    pending: PendingConfirmation,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                pending.title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            val detail = pending.detail
            if (detail.isNullOrBlank()) {
                Text(
                    "El modelo quiere ejecutar esta acción.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(Spacing.md)
                ) {
                    Text(
                        text = detail,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text("Aprobar", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Rechazar", color = MaterialTheme.colorScheme.error)
            }
        }
    )
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

@Composable
private fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentDisplayIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Buscar en esta conversación",
                        style = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Text(
                    text = if (matchCount == 0) "0/0" else "$currentDisplayIndex/$matchCount",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SearchArrowButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = "Anterior",
            enabled = matchCount > 0,
            onClick = onPrev
        )
        SearchArrowButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = "Siguiente",
            enabled = matchCount > 0,
            onClick = onNext
        )
        SearchArrowButton(
            icon = Icons.Default.Close,
            contentDescription = "Cerrar búsqueda",
            enabled = true,
            onClick = onClose
        )
    }
}

@Composable
private fun SearchArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onBackground
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
