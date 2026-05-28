package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role
import com.localchatbot.presentation.components.atoms.AppLogo
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
    onTap: () -> Unit = {}
) {
    when (message.role) {
        Role.User -> UserBubble(message, modifier, onResend, onEdit, onSaveImage, onTap)
        Role.Assistant, Role.System -> {
            // Si el assistant no tiene contenido visible ni sources, es un mensaje
            // intermedio de tool_calls (necesario en el historial para el modelo,
            // pero no aporta nada al usuario). Ocultarlo.
            val hasVisibleContent = message.content.isNotBlank() ||
                !message.sources.isNullOrEmpty() ||
                message.imageDataUrl != null
            if (hasVisibleContent) {
                AssistantBubble(message, modifier, onCopy, onRegenerate, onSaveImage, onTap)
            } else {
                Spacer(modifier = Modifier.height(0.dp))
            }
        }
        // Los mensajes de tool no se renderizan: el indicador "Buscando" muestra el
        // estado en curso y las sources aparecen como chips bajo el assistant final.
        Role.Tool -> Spacer(modifier = Modifier.height(0.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onResend: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
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
                Box {
                    Box(
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = 18.dp,
                                    bottomEnd = 6.dp
                                )
                            )
                            .background(MaterialTheme.colorScheme.primary)
                            .then(
                                if (actionable) Modifier.combinedClickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = onTap,
                                    onLongClick = { menuOpen = true }
                                ) else Modifier.clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = onTap
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            message.content,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text("Editar") },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                }
                            )
                        }
                        if (onResend != null) {
                            DropdownMenuItem(
                                text = { Text("Reenviar") },
                                onClick = {
                                    menuOpen = false
                                    onResend()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onSaveImage: ((ByteArray) -> Unit)? = null,
    onTap: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val actionable = onCopy != null || onRegenerate != null

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AppLogo(size = 28.dp)
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .then(
                        if (actionable) Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onTap,
                            onLongClick = { menuOpen = true }
                        ) else Modifier.clickable(
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
                    Markdown(content = message.content)
                }
                message.sources?.takeIf { it.isNotEmpty() }?.let { srcs ->
                    SourcesRow(sources = srcs)
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                if (onCopy != null) {
                    DropdownMenuItem(
                        text = { Text("Copiar") },
                        onClick = {
                            menuOpen = false
                            onCopy()
                        }
                    )
                }
                if (onRegenerate != null) {
                    DropdownMenuItem(
                        text = { Text("Regenerar") },
                        onClick = {
                            menuOpen = false
                            onRegenerate()
                        }
                    )
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
