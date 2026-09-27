package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localchatbot.core.state.QueuedMessage
import com.localchatbot.core.theme.Spacing

/**
 * Mensajes escritos mientras el modelo trabajaba, aún sin enviar.
 *
 * Van **todos dentro de un mismo contenedor punteado** y no como burbujas sueltas a
 * propósito: al terminar el turno se envían **fusionados en un único mensaje**, así que
 * pintarlos como tres burbujas independientes prometería tres mensajes que nunca van a
 * existir. El contenedor enseña dónde van a caer sin mentir sobre cuántos son.
 *
 * @param onSendNow si no es null, se muestra "Enviar ahora": es la salida cuando el turno
 *   acabó sin vaciar la cola (el usuario pulsó Stop, el turno falló, o el modelo dejó una
 *   pregunta abierta). Sin ese botón la cola quedaría sin forma de enviarse.
 */
@Composable
fun QueuedMessagesCard(
    messages: List<QueuedMessage>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSendNow: (() -> Unit)? = null
) {
    if (messages.isEmpty()) return
    val outline = MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val radius = CornerRadius(12.dp.toPx())
                val inset = 1.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = radius,
                    style = Stroke(
                        width = inset,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                        )
                    )
                )
            }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        messages.forEach { queued ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = queued.text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { onRemove(queued.id) }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Quitar de la cola",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (onSendNow != null) "En cola, sin enviar" else "Se enviarán juntos al terminar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onSendNow != null) {
                TextButton(onClick = onSendNow) {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Enviar ahora",
                        modifier = Modifier.padding(start = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
