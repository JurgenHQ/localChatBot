package com.localchatbot.presentation.features.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.debug.NetworkTransaction
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun NetworkInspectorScreen(
    inspector: NetworkInspector,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by inspector.entries.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<NetworkTransaction?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                title = if (selected == null) "Inspector de red" else "Detalle",
                onBack = {
                    if (selected != null) selected = null else onClose()
                },
                trailing = {
                    if (selected == null && entries.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .clickable { inspector.clear() }
                                .padding(Spacing.sm),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Limpiar",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            )

            val current = selected
            if (current != null) {
                TransactionDetail(current)
            } else {
                TransactionList(
                    entries = entries,
                    onSelect = { selected = it }
                )
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
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
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        trailing()
    }
}

@Composable
private fun TransactionList(
    entries: List<NetworkTransaction>,
    onSelect: (NetworkTransaction) -> Unit
) {
    if (entries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Aún no hay llamadas registradas. Envía un mensaje para empezar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg)
    ) {
        items(entries, key = { it.id }) { tx ->
            TransactionRow(tx, onClick = { onSelect(tx) })
        }
    }
}

@Composable
private fun TransactionRow(tx: NetworkTransaction, onClick: () -> Unit) {
    val statusColor = when {
        tx.error != null -> MaterialTheme.colorScheme.error
        (tx.responseStatus ?: 0) in 200..299 -> Color(0xFF2EBD66)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${tx.method} · ${tx.kind.name}",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = tx.error ?: (tx.responseStatus?.toString() ?: "—"),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = statusColor
            )
        }
        Text(
            text = tx.url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = "${formatTime(tx.timestampEpochMs)}  ·  ${tx.durationMs} ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Identifica en qué bloque (request o response) está una coincidencia. */
private enum class MatchBlock { Request, Response }

private data class MatchPosition(val block: MatchBlock, val offset: Int)

@Composable
private fun TransactionDetail(tx: NetworkTransaction) {
    var query by remember(tx.id) { mutableStateOf("") }
    var currentIndex by remember(tx.id) { mutableStateOf(0) }

    val requestBody = tx.requestBody.orEmpty()
    val responseBody = tx.responseBody.orEmpty()

    // Recalcula las coincidencias cada vez que cambia la query.
    val matches = remember(query, requestBody, responseBody) {
        if (query.isBlank()) emptyList()
        else buildList {
            findAll(requestBody, query).forEach { add(MatchPosition(MatchBlock.Request, it)) }
            findAll(responseBody, query).forEach { add(MatchPosition(MatchBlock.Response, it)) }
        }
    }

    // Si la lista cambia, vuelve al primer match.
    LaunchedEffect(matches) {
        currentIndex = 0
    }

    val scrollState = rememberScrollState()
    var requestLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var responseLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var requestBlockY by remember { mutableStateOf(0) }
    var responseBlockY by remember { mutableStateOf(0) }

    // Auto-scroll al match actual.
    LaunchedEffect(currentIndex, matches, requestLayout, responseLayout) {
        if (matches.isEmpty()) return@LaunchedEffect
        val safe = currentIndex.coerceIn(0, matches.lastIndex)
        val m = matches[safe]
        val (layout, baseY) = when (m.block) {
            MatchBlock.Request -> requestLayout to requestBlockY
            MatchBlock.Response -> responseLayout to responseBlockY
        }
        val l = layout ?: return@LaunchedEffect
        val safeOffset = m.offset.coerceIn(0, (l.layoutInput.text.length - 1).coerceAtLeast(0))
        val box = runCatching { l.getBoundingBox(safeOffset) }.getOrNull() ?: return@LaunchedEffect
        val target = (baseY + box.top.toInt() - 120).coerceAtLeast(0)
            .coerceAtMost(scrollState.maxValue)
        scrollState.animateScrollTo(target)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = { query = it },
            matchCount = matches.size,
            currentDisplayIndex = if (matches.isEmpty()) 0 else currentIndex + 1,
            onPrev = {
                if (matches.isNotEmpty()) {
                    currentIndex = (currentIndex - 1 + matches.size) % matches.size
                }
            },
            onNext = {
                if (matches.isNotEmpty()) {
                    currentIndex = (currentIndex + 1) % matches.size
                }
            },
            onClear = { query = "" }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            DetailMeta(label = "URL", value = "${tx.method} ${tx.url}")
            DetailMeta(label = "Tipo", value = tx.kind.name)
            DetailMeta(label = "Estado", value = tx.responseStatus?.toString() ?: (tx.error ?: "—"))
            DetailMeta(label = "Duración", value = "${tx.durationMs} ms")
            DetailMeta(label = "Hora", value = formatTime(tx.timestampEpochMs))
            tx.error?.let { DetailMeta(label = "Error", value = it) }

            val currentMatch = matches.getOrNull(currentIndex)

            CodeBlock(
                title = "Request",
                content = requestBody,
                query = query,
                currentOffset = if (currentMatch?.block == MatchBlock.Request) currentMatch.offset else null,
                onLayout = { requestLayout = it },
                minContentHeight = 120.dp,
                modifier = Modifier.onGloballyPositioned { coords ->
                    requestBlockY = coords.boundsInParent().top.toInt()
                }
            )
            CodeBlock(
                title = "Response",
                content = responseBody,
                query = query,
                currentOffset = if (currentMatch?.block == MatchBlock.Response) currentMatch.offset else null,
                onLayout = { responseLayout = it },
                modifier = Modifier.onGloballyPositioned { coords ->
                    responseBlockY = coords.boundsInParent().top.toInt()
                }
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentDisplayIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Buscar en request/response…",
                        style = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Limpiar búsqueda",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onClear)
                )
            }
        }

        if (query.isNotEmpty()) {
            Text(
                text = if (matchCount == 0) "0/0" else "$currentDisplayIndex/$matchCount",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ArrowButton(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Anterior",
                enabled = matchCount > 0,
                onClick = onPrev
            )
            ArrowButton(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Siguiente",
                enabled = matchCount > 0,
                onClick = onNext
            )
        }
    }
}

@Composable
private fun ArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onBackground
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun DetailMeta(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun CodeBlock(
    title: String,
    content: String,
    query: String,
    currentOffset: Int?,
    onLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
    minContentHeight: androidx.compose.ui.unit.Dp = 0.dp
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minContentHeight)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surface)
                .padding(Spacing.md)
        ) {
            if (content.isBlank()) {
                Text(
                    text = "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val highlightAll = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f)
                val highlightCurrent = MaterialTheme.colorScheme.tertiary
                val annotated = remember(content, query, currentOffset, highlightAll, highlightCurrent) {
                    buildHighlight(content, query, currentOffset, highlightAll, highlightCurrent)
                }
                Text(
                    text = annotated,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    onTextLayout = onLayout
                )
            }
        }
    }
}

private fun findAll(haystack: String, needle: String): List<Int> {
    if (needle.isEmpty() || haystack.isEmpty()) return emptyList()
    val out = mutableListOf<Int>()
    var i = haystack.indexOf(needle, 0, ignoreCase = true)
    while (i >= 0) {
        out.add(i)
        i = haystack.indexOf(needle, i + 1, ignoreCase = true)
    }
    return out
}

private fun buildHighlight(
    content: String,
    query: String,
    currentOffset: Int?,
    highlightAll: Color,
    highlightCurrent: Color
): AnnotatedString = buildAnnotatedString {
    append(content)
    if (query.isBlank()) return@buildAnnotatedString
    val len = query.length
    findAll(content, query).forEach { start ->
        val end = (start + len).coerceAtMost(content.length)
        val bg = if (start == currentOffset) highlightCurrent else highlightAll
        addStyle(SpanStyle(background = bg), start, end)
    }
}

private fun formatTime(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val h = dt.hour.toString().padStart(2, '0')
    val m = dt.minute.toString().padStart(2, '0')
    val s = dt.second.toString().padStart(2, '0')
    return "$h:$m:$s"
}
