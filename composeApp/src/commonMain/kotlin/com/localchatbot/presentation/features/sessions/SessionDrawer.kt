package com.localchatbot.presentation.features.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.StatusDot
import com.localchatbot.presentation.components.molecules.SessionRow
import com.localchatbot.presentation.preview.PreviewData
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun SessionDrawer(
    viewModel: SessionsViewModel,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionDrawerContent(
        sessions = state.filtered,
        query = state.query,
        connectionLabel = state.connectionLabel,
        onQueryChange = viewModel::onQueryChange,
        onSelect = viewModel::selectSession,
        onDelete = viewModel::deleteSession,
        onNew = {
            viewModel.newSession()
            onNewSession()
        },
        onRename = viewModel::renameSession,
        onTogglePin = viewModel::togglePinned,
        onOpenSettings = {
            viewModel.closeDrawer()
            onOpenSettings()
        },
        onDismiss = viewModel::closeDrawer,
        modifier = modifier
    )
}

@Composable
fun SessionDrawerContent(
    sessions: List<ChatSession>,
    query: String,
    connectionLabel: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onTogglePin: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 40.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "LocalChatBot",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        connectionLabel,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusDot(color = Color(0xFF2EBD66))
            }

            AppTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "  Buscar conversación"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
                    .clickable(onClick = onNew)
                    .padding(vertical = Spacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "Nueva conversación",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onClick = { onSelect(session.id) },
                        onDelete = { onDelete(session.id) },
                        onRename = { newTitle -> onRename(session.id, newTitle) },
                        onTogglePin = { onTogglePin(session.id) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Configuración", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss)
        )
    }
}

@Preview
@Composable
private fun SessionDrawerPreview() = PreviewSurface {
    SessionDrawerContent(
        sessions = PreviewData.sessionList,
        query = "",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onOpenSettings = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun SessionDrawerWithSearchPreview() = PreviewSurface {
    SessionDrawerContent(
        sessions = PreviewData.sessionList.filter { it.title.contains("auth", ignoreCase = true) },
        query = "auth",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onOpenSettings = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun SessionDrawerEmptyPreview() = PreviewSurface {
    SessionDrawerContent(
        sessions = emptyList(),
        query = "",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onOpenSettings = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun SessionDrawerDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    SessionDrawerContent(
        sessions = PreviewData.sessionList,
        query = "",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onOpenSettings = {}, onDismiss = {}
    )
}
