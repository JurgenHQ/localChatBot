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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localchatbot.core.state.QueuedMessage
import com.localchatbot.core.state.ToolActivity
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.tools.RunCommandTool
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.Role
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.atoms.TypingIndicator
import com.localchatbot.presentation.components.molecules.MessageBubble
import com.localchatbot.presentation.components.molecules.QueuedMessagesCard
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
    /** Mensajes en cola, aún sin enviar. Se pintan al fondo del todo. */
    queuedMessages: List<QueuedMessage> = emptyList(),
    onRemoveQueued: (String) -> Unit = {},
    /** No null cuando la cola puede enviarse ya (no hay turno en curso). */
    onSendQueuedNow: (() -> Unit)? = null,
    /** Copia el turno que arranca en ese mensaje del usuario, como Markdown. */
    onCopyTurn: ((String) -> Unit)? = null,
    /**
     * Mensaje al que desplazarse (lo pide la búsqueda global). El cálculo del índice vive
     * aquí y no en la pantalla porque depende de cosas que solo conoce esta lista: se
     * renderiza **filtrada** (los mensajes que no pintan nada se omiten), invertida, y con
     * items extra delante — la cola y la fila de typing/actividad. Con los índices del
     * historial completo el scroll caería en otro mensaje.
     */
    scrollToMessageId: String? = null,
    /** Se llama con el id una vez hecho el scroll, para que no se repita. */
    onScrolledToMessage: (String) -> Unit = {},
    /** Mensaje a marcar tras el salto; comparte el resaltado de "coincidencia actual". */
    highlightedMessageId: String? = null,
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

    // Items que van ANTES de los mensajes en la LazyColumn (con reverseLayout ocupan las
    // posiciones más bajas). Hay que sumarlos al índice o el scroll se queda corto.
    val leadingItemCount = (if (queuedMessages.isNotEmpty()) 1 else 0) +
        (if (toolActivity != null || showTyping) 1 else 0)

    // Depende del tamaño de la lista visible porque al elegir un resultado de búsqueda la
    // sesión cambia primero y sus mensajes llegan después: hasta entonces no hay a dónde ir.
    LaunchedEffect(scrollToMessageId, visibleMessages.size, leadingItemCount) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        val pos = visibleMessages.indexOfFirst { it.id == target }
        // -1 también cuando el mensaje existe pero el filtro no lo pinta (p. ej. un resultado
        // en un mensaje que no se renderiza): sin nada que mostrar, mejor no mover la vista.
        if (pos < 0) return@LaunchedEffect
        listState.animateScrollToItem(leadingItemCount + (visibleMessages.size - 1 - pos))
        onScrolledToMessage(target)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = PaddingValues(vertical = Spacing.lg)
    ) {
        // Con reverseLayout = true el primer item queda abajo.
        // Orden: cola → typing/activity → mensajes invertidos → DayHeader arriba.
        // La cola va primero para quedar pegada al composer, que es donde el usuario acaba
        // de escribir esos mensajes.
        if (queuedMessages.isNotEmpty()) {
            item(key = "queued_messages") {
                QueuedMessagesCard(
                    messages = queuedMessages,
                    onRemove = onRemoveQueued,
                    onSendNow = onSendQueuedNow
                )
            }
        }
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
                onCopy = when {
                    msg.role == Role.Assistant && msg.content.isNotBlank() ->
                        { -> clipboard.setText(AnnotatedString(msg.content)) }
                    // En el usuario, copiar significa copiar el turno entero como Markdown.
                    msg.role == Role.User && onCopyTurn != null -> { -> onCopyTurn(msg.id) }
                    else -> null
                },
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
                // Reutiliza el resaltado de "coincidencia actual" (borde amarillo) para el
                // mensaje al que se acaba de saltar: es el mismo significado —"es este"— y
                // tener dos estilos distintos para eso solo confundiría.
                isCurrentMatch = originalIdx == currentMatchAbsIndex || msg.id == highlightedMessageId,
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
