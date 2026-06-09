package com.localchatbot.presentation.components.molecules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.localchatbot.domain.tools.RunCommandTool
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.util.SelectableOnDesktop
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.m3.Markdown
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
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {},
    highlightQuery: String? = null,
    isCurrentMatch: Boolean = false
) {
    when (message.role) {
        Role.User -> UserBubble(message, modifier, onResend, onEdit, onSaveImage, onTap, highlightQuery, isCurrentMatch)
        Role.Assistant, Role.System -> {
            // Si el assistant no tiene contenido visible ni sources, es un mensaje
            // intermedio de tool_calls (necesario en el historial para el modelo,
            // pero no aporta nada al usuario). Ocultarlo.
            val hasVisibleContent = message.content.isNotBlank() ||
                !message.sources.isNullOrEmpty() ||
                message.imageDataUrl != null
            if (hasVisibleContent) {
                AssistantBubble(message, modifier, onCopy, onRegenerate, onSaveImage, onTap, highlightQuery, isCurrentMatch)
            } else {
                Spacer(modifier = Modifier.height(0.dp))
            }
        }
        Role.Tool -> {
            if (message.toolName == RunCommandTool.TOOL_NAME) {
                TerminalOutputBubble(message, modifier)
            }
            // Otras tools (search_web, render_diagram, etc.) no se renderizan:
            // sus resultados aparecen como sources/imagen bajo el assistant.
        }
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onResend: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {},
    highlightQuery: String? = null,
    isCurrentMatch: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val actionable = onResend != null || onEdit != null

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
            if (message.content.isNotBlank() && message.content != "(imagen)") {
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
                if (actionable) {
                    Row(
                        modifier = Modifier.padding(top = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (onEdit != null) {
                            BubbleActionIcon(Icons.Default.Edit, "Editar", onEdit)
                        }
                        if (onResend != null) {
                            BubbleActionIcon(Icons.AutoMirrored.Filled.Send, "Reenviar", onResend)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {},
    highlightQuery: String? = null,
    isCurrentMatch: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val actionable = onCopy != null || onRegenerate != null

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
            if (message.content.isNotBlank()) {
                val hasMatch = !highlightQuery.isNullOrBlank() &&
                    message.content.contains(highlightQuery, ignoreCase = true)
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
                        Markdown(
                            content = message.content,
                            components = codeBlockComponents()
                        )
                    }
                }
            }
            message.sources?.takeIf { it.isNotEmpty() }?.let { srcs ->
                SourcesRow(sources = srcs)
            }
            if (actionable) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (onCopy != null) {
                        BubbleActionIcon(Icons.Default.ContentCopy, "Copiar mensaje", onCopy)
                    }
                    if (onRegenerate != null) {
                        BubbleActionIcon(Icons.Default.Refresh, "Regenerar", onRegenerate)
                    }
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

@Composable
private fun ImagePreviewDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?
) {
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
                    onClick = onDismiss
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
                        .clip(RoundedCornerShape(16.dp)),
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
    onClick: () -> Unit
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Code block with copy button
// ---------------------------------------------------------------------------

@Composable
private fun codeBlockComponents() = markdownComponents(
    codeFence = {
        MarkdownCodeFence(it.content, it.node) { code, _ -> CopyableCodeBlock(code) }
    },
    codeBlock = {
        MarkdownCodeBlock(it.content, it.node) { code, _ -> CopyableCodeBlock(code) }
    }
)

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
                    .padding(padding.codeBlock),
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
