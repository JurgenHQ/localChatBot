package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.localchatbot.core.image.decodeImage
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.presentation.components.atoms.ChatInputField
import com.localchatbot.presentation.components.atoms.IconSquareButton
import com.localchatbot.presentation.components.atoms.SendIconButton

@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    sending: Boolean = false,
    attachedImageBytes: ByteArray? = null,
    onRemoveAttachment: () -> Unit = {},
    onVoice: () -> Unit = {},
    onStop: () -> Unit = {},
    onTemplates: (() -> Unit)? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissAndSend: () -> Unit = {
        keyboard?.hide()
        onSend()
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        if (attachedImageBytes != null) {
            AttachmentPreview(
                bytes = attachedImageBytes,
                onRemove = onRemoveAttachment,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            IconSquareButton(icon = Icons.Outlined.AttachFile, onClick = onAttach)
            if (onTemplates != null) {
                IconSquareButton(icon = Icons.Outlined.Bookmarks, onClick = onTemplates)
            }
            ChatInputField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f)
            )
            val hasContent = value.isNotBlank() || attachedImageBytes != null
            when {
                sending -> StopIconButton(onClick = onStop)
                hasContent -> SendIconButton(enabled = true, onClick = dismissAndSend)
                else -> IconSquareButton(icon = Icons.Outlined.Mic, onClick = onVoice)
            }
        }
    }
}

@Composable
private fun StopIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Stop,
            contentDescription = "Detener",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun AttachmentPreview(
    bytes: ByteArray,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(bytes) { decodeImage(bytes) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Adjunto",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE84A4A))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Quitar imagen",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
