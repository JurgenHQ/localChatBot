package com.localchatbot.presentation.features.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.voice.VoiceMode

@Composable
fun VoiceConversationSheet(
    mode: VoiceMode,
    onClose: () -> Unit,
    onSubmit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = statusTitle(mode),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            val listening = mode is VoiceMode.Listening
            PulsingMic(
                active = listening,
                onClick = if (listening) onSubmit else null
            )

            if (listening) {
                Text(
                    text = "Toca el micrófono para enviar",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = statusDetail(mode),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Box(
                modifier = Modifier
                    .padding(top = Spacing.lg)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE84A4A))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "Salir del modo voz",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun PulsingMic(active: Boolean, onClick: (() -> Unit)?) {
    val transition = rememberInfiniteTransition(label = "mic")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(44.dp)
        )
    }
}

private fun statusTitle(mode: VoiceMode): String = when (mode) {
    VoiceMode.Off -> ""
    VoiceMode.RequestingPermission -> "Solicitando permiso…"
    is VoiceMode.Listening -> "Escuchando…"
    is VoiceMode.Thinking -> "Pensando…"
    is VoiceMode.Speaking -> "Respondiendo…"
    is VoiceMode.Error -> "Error"
}

private fun statusDetail(mode: VoiceMode): String = when (mode) {
    VoiceMode.Off -> ""
    VoiceMode.RequestingPermission -> "Concede el acceso al micrófono para continuar."
    is VoiceMode.Listening -> mode.partial.ifBlank { "Habla cuando quieras." }
    is VoiceMode.Thinking -> mode.userText
    is VoiceMode.Speaking -> "La respuesta completa está en el chat."
    is VoiceMode.Error -> mode.message
}
