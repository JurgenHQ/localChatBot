package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing

/**
 * Indicador visual del uso del contexto: muestra los tokens estimados consumidos por
 * la conversación frente al tamaño máximo de contexto, con una barra y un texto.
 *
 * La estimación es muy gruesa (≈ 1 token cada 4 caracteres) — no pretende ser exacta,
 * solo dar al usuario una idea del consumo para que sepa cuándo abrir una sesión nueva.
 */
@Composable
fun ContextUsageBar(
    tokensUsed: Int,
    tokensMax: Int,
    /**
     * True si la sesión tiene compactación manual activa. Se marca porque el número de
     * la barra sigue midiendo la conversación **visible**: hasta el próximo turno (cuando
     * llegan las métricas reales del servidor) no refleja el ahorro, y sin el aviso parece
     * que compactar no hizo nada.
     */
    compacted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val pct = if (tokensMax <= 0) 0f else (tokensUsed.toFloat() / tokensMax).coerceIn(0f, 1f)
    val color = when {
        pct >= 0.9f -> MaterialTheme.colorScheme.error
        pct >= 0.7f -> androidx.compose.ui.graphics.Color(0xFFFF8A00)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(pct)
                    .background(color)
            )
        }
        Text(
            text = (if (compacted) "⧉ " else "") + "${formatTokens(tokensUsed)} / ${formatTokens(tokensMax)} tok",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTokens(n: Int): String =
    if (n >= 1000) "${(n / 100) / 10.0}k" else n.toString()

/**
 * Aproximación heurística: ~1 token por cada 4 caracteres de texto.
 * Útil como indicador rápido; no reemplaza un tokenizer real (BPE, tiktoken, etc.).
 */
fun estimateTokens(text: String): Int = (text.length + 3) / 4
