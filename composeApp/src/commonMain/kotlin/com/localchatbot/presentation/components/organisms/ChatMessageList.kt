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
    onSaveImage: (ByteArray) -> Unit,
    onTapMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val visibleMessages = session.messages
    val lastAssistantId = visibleMessages
        .lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
        ?.id
    val lastVisible = visibleMessages.lastOrNull { it.role != Role.Tool }
    val showTyping = sending && toolActivity == null && (
        lastVisible == null ||
        lastVisible.role == Role.User ||
        (lastVisible.role == Role.Assistant && lastVisible.content.isBlank())
    )

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

        itemsIndexed(visibleMessages.reversed(), key = { _, msg -> msg.id }) { reversedIdx, msg ->
            val originalIdx = visibleMessages.size - 1 - reversedIdx
            MessageBubble(
                message = msg,
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
                onSaveImage = if (msg.imageDataUrl != null) onSaveImage else null,
                onTap = onTapMessage,
                highlightQuery = highlightQuery,
                isCurrentMatch = originalIdx == currentMatchAbsIndex
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
