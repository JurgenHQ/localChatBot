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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    voiceController: VoiceConversationController,
    onOpenDrawer: () -> Unit,
    onChangeModel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by chatViewModel.state.collectAsStateWithLifecycle()
    val voiceMode by voiceController.mode.collectAsStateWithLifecycle()
    val imagePicker = rememberImagePicker(onResult = chatViewModel::onImagePicked)
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
            onDismissError = chatViewModel::dismissError,
            voiceSupported = PlatformCapabilities.voiceSupported
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
    onDismissError: () -> Unit = {},
    voiceSupported: Boolean = true,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
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
                { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }
            } else null
        )

        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("Buscar en esta conversación") },
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .padding(end = Spacing.sm)
                            .pointerInput(Unit) { detectTapGestures { searchQuery = ""; searchOpen = false } }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar búsqueda")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)
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
                suggestions = listOf(
                    "Explícame el patrón Repository",
                    "Revisa este snippet de Kotlin",
                    "Resume un texto largo"
                ),
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
                val visibleMessages = if (searchOpen && searchQuery.isNotBlank()) {
                    active.messages.filter { it.content.contains(searchQuery, ignoreCase = true) }
                } else {
                    active.messages
                }
                val lastAssistantId = visibleMessages
                    .lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
                    ?.id
                items(visibleMessages, key = { it.id }) { msg ->
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
                        onTap = { keyboard?.hide() }
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
            voiceSupported = voiceSupported
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
