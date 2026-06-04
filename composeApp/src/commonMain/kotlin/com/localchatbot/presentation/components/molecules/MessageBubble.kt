package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localchatbot.core.image.decodeImage
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.util.SelectableOnDesktop
import com.mikepenz.markdown.m3.Markdown
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // Los mensajes de tool no se renderizan: el indicador "Buscando" muestra el
        // estado en curso y las sources aparecen como chips bajo el assistant final.
        Role.Tool -> Spacer(modifier = Modifier.height(0.dp))
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
                        Markdown(content = message.content)
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
