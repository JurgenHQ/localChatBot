package com.localchatbot.presentation.components.molecules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localchatbot.core.image.decodeImage
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.model.TokenMetrics
import com.localchatbot.domain.tools.RunCommandTool
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.util.SelectableOnDesktop
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onResend: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    isSpeaking: Boolean = false,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {},
    highlightQuery: String? = null,
    isCurrentMatch: Boolean = false,
    isStreaming: Boolean = false,
    /** Si no es null, las referencias a archivos del workspace se vuelven clicables y abren el editor. */
    onOpenFileInEditor: ((String, Int?) -> Unit)? = null,
    /** Si no es null y el mensaje tiene checkpoint, muestra el chip "revertir este turno". */
    onRevertTurn: ((String) -> Unit)? = null
) {
    when (message.role) {
        // En un mensaje del usuario, `onCopy` copia el TURNO entero (su mensaje + la
        // respuesta), no solo el texto de la burbuja: es lo que se quiere al copiar
        // "esto que pasó acá". En el assistant sigue copiando ese mensaje.
        Role.User -> UserBubble(message, modifier, onResend, onEdit, onCopy, onSaveImage, onTap, highlightQuery, isCurrentMatch)
        Role.Assistant, Role.System -> {
            // Si el assistant no tiene contenido visible ni sources ni reasoning, es un mensaje
            // intermedio de tool_calls (necesario en el historial para el modelo,
            // pero no aporta nada al usuario). Ocultarlo.
            val hasVisibleContent = message.content.isNotBlank() ||
                !message.sources.isNullOrEmpty() ||
                message.imageDataUrl != null ||
                message.videoDataUrl != null ||
                !message.reasoning.isNullOrBlank()
            Column(modifier = Modifier.fillMaxWidth()) {
                if (hasVisibleContent) {
                    AssistantBubble(message, modifier, onCopy, onRegenerate, onSpeak, isSpeaking, onSaveImage, onTap, highlightQuery, isCurrentMatch, isStreaming, onOpenFileInEditor)
                }
                // El chip se renderiza aunque el bubble esté oculto: el mensaje que
                // anuncia tool_calls suele tener content vacío.
                if (message.checkpointId != null && onRevertTurn != null && !isStreaming) {
                    RevertTurnChip(onClick = { onRevertTurn(message.checkpointId) })
                }
            }
        }
        Role.Tool -> {
            when (message.toolName) {
                RunCommandTool.TOOL_NAME -> TerminalOutputBubble(message, modifier)
                "edit_file", "multi_edit",
                "create_file", "create_directory",
                "save_image", "save_video",
                "delete_file" -> FileActionBubble(message, modifier)
            }
        }
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onResend: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    /** Copia el turno completo (este mensaje + la respuesta) como Markdown. */
    onCopyTurn: (() -> Unit)? = null,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {},
    highlightQuery: String? = null,
    isCurrentMatch: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val actionable = onResend != null || onEdit != null || onCopyTurn != null

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (message.imageDataUrl != null) {
                AttachedImage(
                    dataUrl = message.imageDataUrl,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    onSave = onSaveImage
                )
            }
            // Chips de archivos adjuntos: el contenido va al modelo, no a la burbuja.
            message.attachments?.takeIf { it.isNotEmpty() }?.let { atts ->
                Column(
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    atts.forEach { AttachedFileChip(it.name) }
                }
            }
            val hasText = message.content.isNotBlank() && message.content != "(imagen)"
            if (hasText) {
                val bubbleShape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 6.dp
                )
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .matchOutline(isCurrentMatch, bubbleShape)
                        .then(
                            if (PlatformCapabilities.isDesktop) Modifier
                            else Modifier.clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = onTap
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    SelectableOnDesktop {
                        Text(
                            text = highlightedText(
                                message.content,
                                highlightQuery,
                                isCurrentMatch
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            if (actionable && (hasText || !message.attachments.isNullOrEmpty())) {
                Row(
                    modifier = Modifier.padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (onEdit != null) {
                        BubbleActionIcon(Icons.Default.Edit, "Editar", onEdit)
                    }
                    if (onCopyTurn != null) {
                        BubbleActionIcon(Icons.Default.ContentCopy, "Copiar turno", onCopyTurn)
                    }
                    if (onResend != null) {
                        BubbleActionIcon(Icons.AutoMirrored.Filled.Send, "Reenviar", onResend)
                    }
                }
            }
        }
    }
}

/** Chip compacto con el nombre de un archivo adjunto del usuario (sin el contenido). */
@Composable
private fun AttachedFileChip(name: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Spacing.sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(14.dp)
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    isSpeaking: Boolean = false,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {},
    highlightQuery: String? = null,
    isCurrentMatch: Boolean = false,
    isStreaming: Boolean = false,
    onOpenFileInEditor: ((String, Int?) -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val actionable = onCopy != null || onRegenerate != null || onSpeak != null

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AppLogo(size = 28.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp)
                .then(
                    if (PlatformCapabilities.isDesktop) Modifier
                    else Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onTap
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (message.imageDataUrl != null) {
                AttachedImage(dataUrl = message.imageDataUrl, onSave = onSaveImage)
            }
            if (message.videoDataUrl != null) {
                GeneratedVideoChip()
            }
            message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                ReasoningPanel(
                    reasoning = reasoning,
                    live = isStreaming && message.content.isBlank(),
                    durationMs = message.metrics?.reasoningMs
                )
            }
            if (message.content.isNotBlank()) {
                val parsed = remember(message.content) { parseTaskListBlocks(message.content) }
                val displayContent = parsed.stripped
                val hasMatch = !highlightQuery.isNullOrBlank() &&
                    message.content.contains(highlightQuery, ignoreCase = true)

                // Render parsed task groups (from <task_list> blocks)
                parsed.taskGroups.forEach { tasks ->
                    InlineTaskList(tasks = tasks)
                }

                if (displayContent.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .matchOutline(isCurrentMatch, RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrentMatch) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
                                else if (hasMatch) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.04f)
                                else Color.Transparent
                            )
                            .padding(if (hasMatch) 4.dp else 0.dp)
                    ) {
                        SelectableOnDesktop {
                            // Markdown no soporta inline-highlight directo. Cuando hay
                            // match, el modelo todavía ve la salida con formato; el
                            // resaltado se logra con el background/border del Box.
                            if (onOpenFileInEditor != null) {
                                FileAwareMarkdown(displayContent, onOpenFileInEditor)
                            } else {
                                Markdown(
                                    content = displayContent,
                                    components = codeBlockComponents()
                                )
                            }
                        }
                    }
                }
            }
            message.sources?.takeIf { it.isNotEmpty() }?.let { srcs ->
                SourcesRow(sources = srcs)
            }
            if (actionable || message.metrics != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (onCopy != null) {
                        BubbleActionIcon(Icons.Default.ContentCopy, "Copiar mensaje", onCopy)
                    }
                    if (onRegenerate != null) {
                        BubbleActionIcon(Icons.Default.Refresh, "Regenerar", onRegenerate)
                    }
                    if (onSpeak != null) {
                        BubbleActionIcon(
                            icon = if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isSpeaking) "Detener lectura" else "Leer en voz alta",
                            tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onSpeak
                        )
                    }
                    message.metrics?.let { MetricsInfoButton(it) }
                }
            }
        }
    }
}

private data class DecodedImage(val bitmap: ImageBitmap, val bytes: ByteArray)

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun AttachedImage(
    dataUrl: String,
    modifier: Modifier = Modifier,
    onSave: ((ByteArray) -> Unit)? = null
) {
    // Decodifica base64 + PNG en un hilo de IO para no bloquear el UI thread.
    // Las imágenes generadas (SDXL) pueden ser varios MB — la decodificación síncrona
    // en el hilo principal es lo que causaba el congelamiento.
    val decoded by produceState<DecodedImage?>(initialValue = null, key1 = dataUrl) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
                if (base64.isEmpty()) return@runCatching null
                val bytes = Base64.decode(base64)
                val bitmap = decodeImage(bytes) ?: return@runCatching null
                DecodedImage(bitmap, bytes)
            }.getOrNull()
        }
    }

    var previewOpen by remember { mutableStateOf(false) }

    val d = decoded
    if (d != null) {
        Box(
            modifier = modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { previewOpen = true }
        ) {
            Image(
                bitmap = d.bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        }

        if (previewOpen) {
            ImagePreviewDialog(
                bitmap = d.bitmap,
                onDismiss = { previewOpen = false },
                onSave = if (onSave != null) {
                    {
                        onSave(d.bytes)
                        previewOpen = false
                    }
                } else null
            )
        }
    } else {
        // Placeholder mientras decodifica (solo al primer render)
        Box(
            modifier = modifier
                .widthIn(max = 240.dp)
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

/**
 * Placeholder simple para un video generado (`animate_image`/`cartoon_video`). No hay
 * reproductor embebido en Compose Multiplatform hoy — el usuario le pide al modelo que lo
 * guarde con `save_video` para verlo con su reproductor del sistema.
 */
@Composable
private fun GeneratedVideoChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text("🎬", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Video generado — pídele que lo guarde para verlo",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImagePreviewDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?
) {
    // Zoom + arrastre: pinch (móvil / trackpad) escala entre 1x y 5x; al hacer zoom se
    // puede arrastrar la imagen. Doble-tap alterna entre ajustado (1x) y 2.5x. Resetea
    // el offset al volver a 1x para que la imagen no quede descentrada.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomed = scale > 1f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // Tap en el fondo cierra solo si no hay zoom (con zoom el tap-fuera
                    // podría ser accidental mientras se explora la imagen).
                    onClick = { if (!zoomed) onDismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                // Pan solo tiene sentido con zoom; al volver a 1x, recentrar.
                                offset = if (newScale > 1f) {
                                    val limit = (newScale - 1f) * 600f
                                    Offset(
                                        (offset.x + pan.x).coerceIn(-limit, limit),
                                        (offset.y + pan.y).coerceIn(-limit, limit)
                                    )
                                } else {
                                    Offset.Zero
                                }
                                scale = newScale
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoomed) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        },
                    contentScale = ContentScale.Fit
                )
                if (onSave != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onSave)
                            .padding(horizontal = 28.dp, vertical = 14.dp)
                    ) {
                        Text(
                            "Guardar imagen",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BubbleActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState()
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Métricas de tokens (icono info + popup)
// ---------------------------------------------------------------------------

// Precios de referencia (USD por 1M tokens) — GPT-4o-mini. El coste es hipotético:
// para modelos locales es ~0, pero da una idea de lo que costaría en cloud.
private const val PRICE_INPUT_PER_1M = 0.15
private const val PRICE_OUTPUT_PER_1M = 0.60

@Composable
private fun MetricsInfoButton(metrics: TokenMetrics) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = true }
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "Métricas",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(
                    "Métricas",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.sm))
                val est = metrics.estimated
                MetricRow("Tokens entrada (Tu mensaje + agente)", metrics.inputTokens?.toString() ?: "—")
                MetricRow("Tokens salida (Generado por el modelo)", metrics.outputTokens?.let { (if (est) "~" else "") + it } ?: "—")
                MetricRow("Total", metrics.totalTokens?.let { (if (est) "~" else "") + it } ?: "—")
                metrics.contextTokens?.let { MetricRow("Contexto actual", it.toString()) }
                MetricRow("Velocidad", metrics.tokensPerSecond?.let { "${formatDecimals(it, 1)} tok/s" } ?: "—")
                MetricRow("Coste hipotético", "$" + formatDecimals(hypotheticalCost(metrics), 6))
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    buildString {
                        if (est) append("~ estimado (el servidor no reportó tokens). ")
                        append("Coste ref. GPT-4o-mini.")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(Spacing.lg))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun hypotheticalCost(m: TokenMetrics): Double =
    (m.inputTokens ?: 0) / 1_000_000.0 * PRICE_INPUT_PER_1M +
        (m.outputTokens ?: 0) / 1_000_000.0 * PRICE_OUTPUT_PER_1M

/** Formatea un Double con [decimals] decimales sin depender de String.format (KMP common). */
private fun formatDecimals(v: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(v * factor).toLong()
    val intPart = scaled / factor
    val fracPart = (scaled % factor).toString().padStart(decimals, '0')
    return "$intPart.$fracPart"
}

// ---------------------------------------------------------------------------
// Code block with copy button
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Referencias a archivos clicables (abren el editor)
// ---------------------------------------------------------------------------

/** Esquema sintético para los links de archivo; lo intercepta el UriHandler. */
private const val FILE_LINK_SCHEME = "lcb-file:"

/** Líneas que abren/cierran un bloque de código cercado (``` o ~~~). */
private val FENCE_REGEX = Regex("""^\s*(```|~~~)""")

/** Code-span inline: `texto` (sin backticks ni saltos de línea dentro). */
private val INLINE_CODE_REGEX = Regex("`([^`\\n]+)`")

/** Token con pinta de ruta de archivo: algo.ext, a/b/c.kt, con `:linea` opcional. */
private val FILE_TOKEN_REGEX = Regex("""^[\w.\-/]+\.[A-Za-z0-9]+(?::\d+)?$""")

private val LINE_SUFFIX_REGEX = Regex(""":\d+$""")

/**
 * Reescribe los code-spans que parecen rutas de archivo (`` `src/Foo.kt` ``) como
 * links markdown con esquema [FILE_LINK_SCHEME], conservando el estilo monoespaciado
 * (el texto del link sigue siendo un code-span). Respeta los bloques cercados y no
 * toca code-spans que ya son el texto de un link.
 */
private fun linkifyFileReferences(content: String): String {
    if (!content.contains('`')) return content
    var inFence = false
    return content.split("\n").joinToString("\n") { line ->
        if (FENCE_REGEX.containsMatchIn(line)) {
            inFence = !inFence
            return@joinToString line
        }
        if (inFence) return@joinToString line
        INLINE_CODE_REGEX.replace(line) { m ->
            val inner = m.groupValues[1].trim()
            val followedByLink = line.getOrNull(m.range.last + 1) == ']'
            if (!followedByLink && FILE_TOKEN_REGEX.matches(inner)) {
                "[`$inner`]($FILE_LINK_SCHEME$inner)"
            } else {
                m.value
            }
        }
    }
}

/**
 * Markdown con referencias a archivos clicables. Intercepta los links con esquema
 * [FILE_LINK_SCHEME] vía un [UriHandler] propio y delega el resto al handler de la
 * plataforma (URLs http normales). Extrae el sufijo `:línea` si existe y lo pasa
 * como segundo argumento para que el editor salte a esa línea.
 */
@Composable
private fun FileAwareMarkdown(content: String, onOpenFile: (String, Int?) -> Unit) {
    val platformHandler = LocalUriHandler.current
    val handler = remember(onOpenFile, platformHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(FILE_LINK_SCHEME)) {
                    val raw = uri.removePrefix(FILE_LINK_SCHEME)
                    val lineMatch = LINE_SUFFIX_REGEX.find(raw)
                    val line = lineMatch?.value?.removePrefix(":")?.toIntOrNull()
                    val path = if (lineMatch != null) raw.removeSuffix(lineMatch.value) else raw
                    onOpenFile(path, line)
                } else {
                    runCatching { platformHandler.openUri(uri) }
                }
            }
        }
    }
    val linkified = remember(content) { linkifyFileReferences(content) }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(content = linkified, components = codeBlockComponents())
    }
}

/**
 * Workaround para CMP-8028: en iOS el motor de render de Compose a veces pinta texto
 * en negro ignorando el color especificado (aleatorio, típico con streaming y scroll;
 * sin fix al menos hasta CMP 1.10). Un saveLayer con ColorFilter.tint fuerza el color
 * a nivel de píxeles preservando el alpha (los fondos translúcidos de inline-code se
 * mantienen). En Android/Desktop es un no-op sin coste.
 */
private fun Modifier.forceTextColor(color: Color): Modifier =
    if (!PlatformCapabilities.needsTextColorWorkaround) this
    else drawWithCache {
        val paint = Paint().apply { colorFilter = ColorFilter.tint(color) }
        onDrawWithContent {
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), paint) }
            drawContent()
            drawIntoCanvas { it.restore() }
        }
    }

@Composable
private fun codeBlockComponents() = markdownComponents(
    // Texto de párrafos y headings con color forzado en iOS (ver forceTextColor).
    paragraph = {
        MarkdownParagraph(
            it.content, it.node,
            modifier = Modifier.forceTextColor(LocalMarkdownColors.current.text),
            style = it.typography.paragraph
        )
    },
    heading1 = { HeaderWithForcedColor(it.content, it.node, it.typography.h1) },
    heading2 = { HeaderWithForcedColor(it.content, it.node, it.typography.h2) },
    heading3 = { HeaderWithForcedColor(it.content, it.node, it.typography.h3) },
    heading4 = { HeaderWithForcedColor(it.content, it.node, it.typography.h4) },
    heading5 = { HeaderWithForcedColor(it.content, it.node, it.typography.h5) },
    heading6 = { HeaderWithForcedColor(it.content, it.node, it.typography.h6) },
    codeFence = {
        MarkdownCodeFence(it.content, it.node) { code, _ -> CopyableCodeBlock(code) }
    },
    codeBlock = {
        MarkdownCodeBlock(it.content, it.node) { code, _ -> CopyableCodeBlock(code) }
    },
    // La librería invoca `custom` para todo nodo que no esté en su `when` interno
    // (ver Markdown.handleElement). Como `invoke()` devuelve Unit (siempre `!= null`),
    // el nodo queda marcado como "handled" → la lib NO recursa a sus hijos. Por eso
    // aquí hay que renderizar el contenido completo de cada tipo, no solo tablas.
    custom = { type, model ->
        val src = runCatching {
            model.content.substring(model.node.startOffset, model.node.endOffset)
        }.getOrNull()
        when (type) {
            // Tablas GFM: la lib 0.27.0 no las soporta y, sin esto, apila las celdas
            // en vertical. Recortamos por offsets y dibujamos un grid.
            GFMElementTypes.TABLE -> {
                val rows = src?.let { parseMarkdownTable(it) }
                if (rows != null) MarkdownTableBlock(rows)
                else if (!src.isNullOrBlank()) Text(
                    src,
                    modifier = Modifier.forceTextColor(MaterialTheme.colorScheme.onSurface),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // Cada LIST_ITEM cae aquí (no está en el `when` de la lib). Sin esto su
            // contenido se pintaba como texto crudo SIN color (negro, ilegible en modo
            // oscuro) y sin parsear (`**`, enlaces, marcadores anidados literales).
            // Renderizamos sus hijos con los composables reales de la librería.
            MarkdownElementTypes.LIST_ITEM -> {
                model.node.children.forEach { child ->
                    when (child.type) {
                        MarkdownElementTypes.PARAGRAPH ->
                            MarkdownParagraph(
                                model.content, child,
                                modifier = Modifier.forceTextColor(LocalMarkdownColors.current.text),
                                style = model.typography.paragraph
                            )
                        MarkdownElementTypes.UNORDERED_LIST ->
                            MarkdownBulletList(model.content, child, style = model.typography.bullet)
                        MarkdownElementTypes.ORDERED_LIST ->
                            MarkdownOrderedList(model.content, child, style = model.typography.ordered)
                        MarkdownElementTypes.CODE_FENCE ->
                            MarkdownCodeFence(model.content, child) { code, _ -> CopyableCodeBlock(code) }
                        MarkdownElementTypes.CODE_BLOCK ->
                            MarkdownCodeBlock(model.content, child) { code, _ -> CopyableCodeBlock(code) }
                        // Marcadores y saltos del propio ítem: no se pintan.
                        MarkdownTokenTypes.LIST_BULLET,
                        MarkdownTokenTypes.LIST_NUMBER,
                        MarkdownTokenTypes.WHITE_SPACE,
                        MarkdownTokenTypes.EOL -> Unit
                        // Cualquier otro hijo: texto crudo pero con color legible.
                        else -> runCatching {
                            model.content.substring(child.startOffset, child.endOffset)
                        }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                modifier = Modifier.forceTextColor(MaterialTheme.colorScheme.onSurface),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            // Resto de tipos no soportados: texto crudo con color legible para no
            // perder contenido.
            else -> src?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    modifier = Modifier.forceTextColor(MaterialTheme.colorScheme.onSurface),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
)

/** [MarkdownHeader] no acepta modifier: se envuelve para aplicar [forceTextColor]. */
@Composable
private fun HeaderWithForcedColor(
    content: String,
    node: org.intellij.markdown.ast.ASTNode,
    style: androidx.compose.ui.text.TextStyle
) {
    Box(modifier = Modifier.forceTextColor(LocalMarkdownColors.current.text)) {
        MarkdownHeader(content, node, style)
    }
}

/**
 * Parsea el texto fuente de una tabla GFM en filas de celdas. Devuelve null si no
 * parece una tabla. La fila separadora (`|---|---|`) se descarta.
 */
private fun parseMarkdownTable(src: String): List<List<String>>? {
    fun isSeparator(line: String): Boolean {
        val stripped = line.replace("|", "").replace(":", "").trim()
        return stripped.isNotEmpty() && stripped.all { it == '-' || it.isWhitespace() } && stripped.contains('-')
    }
    fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split("|").map { it.trim() }
    }
    val lines = src.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return null
    val rows = lines.filterNot { isSeparator(it) }.map { splitRow(it) }
    return rows.takeIf { it.isNotEmpty() && it.any { r -> r.size > 1 } }
}

@Composable
private fun MarkdownTableBlock(rows: List<List<String>>) {
    val cols = rows.maxOf { it.size }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        rows.forEachIndexed { rowIdx, cells ->
            val isHeader = rowIdx == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isHeader) headerBg else Color.Transparent)
                    .height(IntrinsicSize.Min)
            ) {
                for (c in 0 until cols) {
                    if (c > 0) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(borderColor)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cells.getOrElse(c) { "" },
                            modifier = Modifier.forceTextColor(MaterialTheme.colorScheme.onSurface),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            if (rowIdx < rows.size - 1) {
                HorizontalDivider(thickness = 1.dp, color = borderColor)
            }
        }
    }
}

@Composable
private fun CopyableCodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    val colors = LocalMarkdownColors.current
    val dimens = LocalMarkdownDimens.current
    val padding = LocalMarkdownPadding.current
    val typography = LocalMarkdownTypography.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Box(modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        MarkdownCodeBackground(
            color = colors.codeBackground,
            shape = RoundedCornerShape(dimens.codeBackgroundCornerSize),
            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
        ) {
            Text(
                text = code,
                color = colors.codeText,
                modifier = androidx.compose.ui.Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(padding.codeBlock)
                    .forceTextColor(colors.codeText),
                style = typography.code
            )
        }
        Box(
            modifier = androidx.compose.ui.Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                .clickable {
                    clipboard.setText(AnnotatedString(code.trimEnd()))
                    scope.launch {
                        copied = true
                        delay(1500)
                        copied = false
                    }
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copiado" else "Copiar",
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.size(12.dp)
                )
                Text(
                    text = if (copied) "Copiado" else "Copiar",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Construye un [AnnotatedString] resaltando todas las apariciones (case-insensitive)
 * de [query] en [text]. Si la burbuja es el match activo el resaltado es naranja
 * sólido; si la burbuja contiene un match pero NO es el activo, queda en amarillo
 * suave para distinguir entre "match" y "match seleccionado".
 */
@Composable
private fun highlightedText(
    text: String,
    query: String?,
    isCurrentMatch: Boolean
): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val activeBg = Color(0xFFFFC107)        // ámbar fuerte
    val passiveBg = Color(0xFFFFF59D)       // amarillo suave
    val bg = if (isCurrentMatch) activeBg else passiveBg
    return buildAnnotatedString {
        append(text)
        var idx = 0
        while (true) {
            val found = text.indexOf(query, idx, ignoreCase = true)
            if (found < 0) break
            addStyle(
                SpanStyle(
                    background = bg,
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold
                ),
                start = found,
                end = found + query.length
            )
            idx = found + query.length
        }
    }
}

/**
 * Aplica un borde llamativo cuando este mensaje es el match activo de la
 * búsqueda. Para los matches NO activos no aplica nada (el highlight in-line
 * en UserBubble + el tinte de fondo en AssistantBubble ya los marca).
 */
private fun Modifier.matchOutline(isCurrentMatch: Boolean, shape: Shape): Modifier =
    if (isCurrentMatch) this.border(width = 2.dp, color = Color(0xFFFFC107), shape = shape)
    else this

// ---------------------------------------------------------------------------
// Task list inline renderer
// ---------------------------------------------------------------------------

private data class ParsedTask(val text: String, val done: Boolean)

/**
 * Extracts all <task_list> blocks from [content] and returns:
 * - stripped: the content with those blocks removed (trimmed)
 * - taskGroups: one list of tasks per <task_list> found
 *
 * Handles tasks as <task>…</task> elements, and also markdown checkbox lines
 * ("- [ ] …" / "- [x] …") inside the XML block. Empty blocks are dropped.
 */
private data class TaskListParseResult(val stripped: String, val taskGroups: List<List<ParsedTask>>)

private val TASK_LIST_BLOCK_REGEX = Regex(
    """<task_list[^>]*>([\s\S]*?)</task_list>""",
    RegexOption.IGNORE_CASE
)
private val TASK_TAG_REGEX = Regex(
    """<task(?:\s+[^>]*)?>([^<]*)</task>""",
    RegexOption.IGNORE_CASE
)
private val TASK_STATUS_DONE_REGEX = Regex("""status\s*=\s*["']?done["']?""", RegexOption.IGNORE_CASE)
private val CHECKBOX_LINE_REGEX = Regex("""^\s*[-*]\s+\[([ xX])\]\s+(.+)$""")

private fun parseTaskListBlocks(content: String): TaskListParseResult {
    if (!content.contains("<task_list", ignoreCase = true)) {
        // Trim leading/trailing whitespace: muchos modelos emiten saltos de línea
        // sobrantes antes de un tool_call, que el markdown renderiza como huecos
        // verticales que se acumulan ronda tras ronda.
        return TaskListParseResult(content.trim(), emptyList())
    }
    val groups = mutableListOf<List<ParsedTask>>()
    val stripped = TASK_LIST_BLOCK_REGEX.replace(content) { match ->
        val inner = match.groupValues[1]
        val tasks = mutableListOf<ParsedTask>()

        // Try <task> tags first
        TASK_TAG_REGEX.findAll(inner).forEach { m ->
            val rawTag = m.value
            val text = m.groupValues[1].trim()
            if (text.isNotBlank()) {
                val done = TASK_STATUS_DONE_REGEX.containsMatchIn(rawTag)
                tasks += ParsedTask(text, done)
            }
        }

        // Fall back to markdown checkbox lines if no <task> tags found
        if (tasks.isEmpty()) {
            inner.lines().forEach { line ->
                val cm = CHECKBOX_LINE_REGEX.matchEntire(line)
                if (cm != null) {
                    val check = cm.groupValues[1]
                    val text = cm.groupValues[2].trim()
                    tasks += ParsedTask(text, check.equals("x", ignoreCase = true))
                } else {
                    val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
                    if (trimmed.isNotBlank()) tasks += ParsedTask(trimmed, false)
                }
            }
        }

        if (tasks.isNotEmpty()) groups += tasks
        "" // remove the block from the markdown content
    }.trim()

    return TaskListParseResult(stripped, groups)
}

@Composable
private fun InlineTaskList(tasks: List<ParsedTask>) {
    val doneCount = tasks.count { it.done }
    val totalCount = tasks.size
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(surfaceColor)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = "Tareas",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = onSurface
            )
            if (totalCount > 0) {
                Text(
                    text = "$doneCount/$totalCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (doneCount == totalCount) MaterialTheme.colorScheme.primary else onSurface
                )
            }
        }
        tasks.forEach { task ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(vertical = 1.dp)
            ) {
                Icon(
                    imageVector = if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (task.done) MaterialTheme.colorScheme.primary else onSurface,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = task.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.done) onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Terminal output collapsible
// ---------------------------------------------------------------------------

private data class TerminalResult(
    val command: String?,
    val exitCode: Int?,
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val isBackground: Boolean,
    val pid: Long?
)

private fun parseTerminalResult(json: String): TerminalResult? = runCatching {
    val obj = Json.parseToJsonElement(json).jsonObject
    TerminalResult(
        command = obj["command"]?.jsonPrimitive?.content,
        exitCode = obj["exitCode"]?.jsonPrimitive?.intOrNull,
        success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false,
        stdout = obj["stdout"]?.jsonPrimitive?.content
            ?: obj["initial_stdout"]?.jsonPrimitive?.content ?: "",
        stderr = obj["stderr"]?.jsonPrimitive?.content
            ?: obj["initial_stderr"]?.jsonPrimitive?.content ?: "",
        isBackground = obj["background"]?.jsonPrimitive?.booleanOrNull ?: false,
        pid = obj["pid"]?.jsonPrimitive?.content?.toLongOrNull()
    )
}.getOrNull()

@Composable
private fun TerminalOutputBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val result = remember(message.content) { parseTerminalResult(message.content) } ?: return
    var expanded by remember { mutableStateOf(false) }

    val hasOutput = result.stdout.isNotBlank() || result.stderr.isNotBlank()
    val shape = RoundedCornerShape(Radius.md)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        // Header row — siempre visible
        Row(
            modifier = Modifier
                .clip(if (expanded && hasOutput) RoundedCornerShape(topStart = Radius.md, topEnd = Radius.md) else shape)
                .background(surfaceColor)
                .then(if (hasOutput) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = onSurface, modifier = Modifier.size(14.dp))
            Text(
                text = result.command?.let { "$ $it" } ?: "Terminal",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            // Badge: exit code / background / error
            when {
                result.isBackground -> {
                    Text(
                        text = "PID ${result.pid ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                result.exitCode != null -> {
                    val ok = result.exitCode == 0
                    Icon(
                        imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = result.exitCode.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            if (hasOutput) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Colapsar" else "Expandir",
                    tint = onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Body — solo visible cuando expandido
        AnimatedVisibility(visible = expanded && hasOutput) {
            val output = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout)
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(result.stderr)
                }
            }.trim()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, surfaceColor, RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md))
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md)
            ) {
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Revert turn chip (checkpoint del turno)
// ---------------------------------------------------------------------------

@Composable
private fun RevertTurnChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(start = Spacing.lg, end = Spacing.lg, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "↩ Revertir cambios de este turno",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// File action chip (edit_file / create_file / delete_file / multi_edit)
// ---------------------------------------------------------------------------

@Composable
private fun FileActionBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val toolName = message.toolName ?: return

    val label = when (toolName) {
        "edit_file", "multi_edit" -> "Editado"
        "create_file", "create_directory" -> "Creado"
        "save_image" -> "Imagen guardada"
        "save_video" -> "Video guardado"
        "delete_file" -> "Eliminado"
        else -> return
    }

    val editedColor = Color(0xFF2EBD66)
    val deletedColor = MaterialTheme.colorScheme.error
    val createdColor = MaterialTheme.colorScheme.primary

    val color = when (toolName) {
        "edit_file", "multi_edit" -> editedColor
        "create_file", "create_directory", "save_image", "save_video" -> createdColor
        else -> deletedColor
    }

    val path = remember(message.content) {
        runCatching {
            Json.parseToJsonElement(message.content).jsonObject.let { obj ->
                if (obj["error"] != null) null
                else obj["_path"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    } ?: return

    val displayPath = remember(path) {
        val parts = path.replace('\\', '/').trimEnd('/').split('/')
        if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}"
        else parts.lastOrNull() ?: path
    }

    Row(
        modifier = modifier.padding(start = Spacing.lg, end = Spacing.lg, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(
            text = "$label  $displayPath",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
