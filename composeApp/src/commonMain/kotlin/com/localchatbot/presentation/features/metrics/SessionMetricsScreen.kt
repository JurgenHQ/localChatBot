package com.localchatbot.presentation.features.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.SessionMetrics
import com.localchatbot.domain.model.aggregateSessionMetrics

/**
 * Panel de métricas agregadas de la sesión activa (roadmap 4.3): tokens/velocidad,
 * coste estimado y tools más usadas. Sin ViewModel — mismo patrón que
 * [com.localchatbot.presentation.features.debug.NetworkInspectorScreen]: la agregación es
 * una función pura sobre [ChatSession.messages], que [session] ya trae cargados (viene de
 * `ChatRepository.sessionWithMessages`, colectado por `ChatViewModel` para la sesión activa).
 */
@Composable
fun SessionMetricsScreen(
    session: ChatSession?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = remember(session) {
        session?.takeIf { it.messages.isNotEmpty() }?.let { aggregateSessionMetrics(it.messages, it.model) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onClose)
            if (metrics == null) {
                EmptyState()
            } else {
                MetricsContent(metrics)
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(onClick = onBack)
                .padding(Spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = "Métricas de la sesión",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Text(
            "Esta sesión todavía no tiene mensajes con métricas.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MetricsContent(metrics: SessionMetrics) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg)
    ) {
        item { TokensCard(metrics) }
        item { PerModelCard(metrics) }
        item { CostCard(metrics) }
        item { ToolsCard(metrics) }
    }
}

@Composable
private fun MetricsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TokensCard(metrics: SessionMetrics) {
    MetricsCard(title = "Tokens") {
        MetricRow("Entrada", formatTokenCount(metrics.totalInputTokens))
        MetricRow("Salida", formatTokenCount(metrics.totalOutputTokens))
        MetricRow("Total", formatTokenCount(metrics.totalInputTokens + metrics.totalOutputTokens))
        MetricRow(
            "Velocidad promedio",
            metrics.avgTokensPerSecond?.let { "${formatFixed(it, 1)} tok/s" } ?: "—"
        )
        MetricRow("Turnos medidos", metrics.turnsWithMetrics.toString())
    }
}

/**
 * Solo aparece si la sesión usó más de un modelo: con uno solo repetiría lo que ya dice
 * la tarjeta de tokens.
 */
@Composable
private fun PerModelCard(metrics: SessionMetrics) {
    if (metrics.perModel.size < 2) return
    MetricsCard(title = "Por modelo") {
        metrics.perModel.forEach { usage ->
            MetricRow(
                usage.model,
                buildString {
                    append(formatTokenCount(usage.inputTokens + usage.outputTokens))
                    append(" tok")
                    usage.tokensPerSecond?.let { append(" · ${formatFixed(it, 1)} tok/s") }
                    append(" · ${usage.turns} turnos")
                }
            )
        }
    }
}

@Composable
private fun CostCard(metrics: SessionMetrics) {
    MetricsCard(title = "Coste estimado") {
        val cost = metrics.estimatedCostUsd
        if (cost == null) {
            Text(
                "No disponible: ningún modelo de esta sesión está en la tabla de precios " +
                    "conocidos (normal para modelos locales).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            MetricRow("Total", "US$ ${formatFixed(cost, 4)}")
            // Un total parcial sin avisar se leería como el coste completo de la sesión.
            if (metrics.modelsWithoutPrice.isNotEmpty()) {
                Text(
                    "Parcial: sin precio conocido para ${metrics.modelsWithoutPrice.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ToolsCard(metrics: SessionMetrics) {
    MetricsCard(title = "Tools más usadas") {
        if (metrics.toolUsageCounts.isEmpty()) {
            Text(
                "Sin tools ejecutadas en esta sesión.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            metrics.toolUsageCounts.take(10).forEach { (name, count) ->
                MetricRow(name, count.toString())
            }
        }
    }
}

private fun formatTokenCount(n: Int): String =
    if (n >= 1000) "${(n / 100) / 10.0}k" else n.toString()

/** Formatea con [decimals] decimales fijos, sin depender de `java.util.Locale` (KMP). */
private fun formatFixed(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val rounded = kotlin.math.round(value * factor).toLong()
    val intPart = rounded / factor.toLong()
    val fracPart = kotlin.math.abs(rounded % factor.toLong())
    return "$intPart.${fracPart.toString().padStart(decimals, '0')}"
}
