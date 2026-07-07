package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localchatbot.core.state.ToolActivity
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.tools.RunCommandTool
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.Role
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.atoms.TypingIndicator
import com.localchatbot.presentation.components.molecules.MessageBubble
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Lista scrolleable de mensajes de la sesión activa: DayHeader + burbujas +
 * fila de actividad de tool / typing indicator al final. Extraída de
 * ChatScreen para mantener esa pantalla en un tamaño razonable.
 */
@Composable
fun ChatMessageList(
    session: ChatSession,
    listState: LazyListState,
    sending: Boolean,
    toolActivity: ToolActivity?,
    highlightQuery: String?,
    currentMatchAbsIndex: Int,
    onResendMessage: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerate: () -> Unit,
    speakingMessageId: String? = null,
    onSpeakMessage: (String, String) -> Unit = { _, _ -> },
    onStopSpeak: () -> Unit = {},
    onSaveImage: (ByteArray) -> Unit,
    onTapMessage: () -> Unit,
    onOpenFileInEditor: ((String, Int?) -> Unit)? = null,
    onRevertTurn: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val allMessages = session.messages
    // Rendering uses a filtered list so LazyColumn spacing doesn't create phantom gaps from
    // messages that render nothing (empty tool-call announcers, tool results without a renderer).
    // Mirrors the visibility rules in MessageBubble. All "working" state below is derived from
    // this same filtered list so the typing indicator never pops up under a reasoning bubble for
    // an invisible announcer message (that produced spurious vertical gaps between rounds).
    val visibleMessages = allMessages.filter { msg ->
        when (msg.role) {
            Role.User -> true
            Role.Assistant, Role.System ->
                msg.content.isNotBlank() ||
                !msg.sources.isNullOrEmpty() ||
                msg.imageDataUrl != null ||
                !msg.reasoning.isNullOrBlank() ||
                // El anunciador de tool_calls con checkpoint se muestra: renderiza
                // (solo) el chip "revertir este turno".
                (msg.checkpointId != null && onRevertTurn != null)
            Role.Tool -> msg.toolName in RENDERED_TOOL_NAMES
        }
    }
    val lastAssistantId = visibleMessages
        .lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
        ?.id
    val lastVisible = visibleMessages.lastOrNull { it.role != Role.Tool }
    val streamingMessageId = if (sending && lastVisible?.role == Role.Assistant) lastVisible.id else null
    val showTyping = sending && toolActivity == null && (
        lastVisible == null ||
        lastVisible.role == Role.User ||
        (lastVisible.role == Role.Assistant && lastVisible.content.isBlank() && lastVisible.reasoning.isNullOrBlank())
    )
    // Map back to absolute indices in the full list so search highlighting (matchIndices in
    // ChatScreen are full-list indices) stays aligned despite the filtering above.
    val absoluteIndexById = allMessages.withIndex().associate { (i, m) -> m.id to i }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = PaddingValues(vertical = Spacing.lg)
    ) {
        // Con reverseLayout = true el primer item queda abajo.
        // Orden: typing/activity → mensajes invertidos → DayHeader arriba.
        if (toolActivity != null) {
            item(key = "tool_activity") {
                ToolActivityRow(label = toolActivity.label, detail = toolActivity.detail)
            }
        } else if (showTyping) {
            item(key = "typing") { AssistantTypingRow() }
        }

        itemsIndexed(visibleMessages.reversed(), key = { _, msg -> msg.id }) { _, msg ->
            val originalIdx = absoluteIndexById[msg.id] ?: -1
            MessageBubble(
                message = msg,
                isStreaming = msg.id == streamingMessageId,
                onResend = if (msg.role == Role.User && !sending) {
                    { onResendMessage(msg.id) }
                } else null,
                onEdit = if (msg.role == Role.User && !sending) {
                    { onEditMessage(msg.id) }
                } else null,
                onCopy = if (msg.role == Role.Assistant && msg.content.isNotBlank()) {
                    { clipboard.setText(AnnotatedString(msg.content)) }
                } else null,
                onRegenerate = if (
                    msg.id == lastAssistantId && !sending
                ) onRegenerate else null,
                onSpeak = if (msg.role == Role.Assistant && msg.content.isNotBlank()) {
                    {
                        if (msg.id == speakingMessageId) onStopSpeak()
                        else onSpeakMessage(msg.id, msg.content)
                    }
                } else null,
                isSpeaking = msg.id == speakingMessageId,
                onSaveImage = if (msg.imageDataUrl != null) onSaveImage else null,
                onTap = onTapMessage,
                highlightQuery = highlightQuery,
                isCurrentMatch = originalIdx == currentMatchAbsIndex,
                onOpenFileInEditor = onOpenFileInEditor,
                onRevertTurn = if (!sending) onRevertTurn else null
            )
        }

        item(key = "day_header") { DayHeader(epochMs = session.createdAtEpochMs) }
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

private val RENDERED_TOOL_NAMES = setOf(
    RunCommandTool.TOOL_NAME,
    "edit_file", "multi_edit", "create_file", "create_directory", "delete_file", "save_image"
)

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
