package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing

@Composable
fun ChatTopBar(
    title: String,
    subtitle: String,
    onMenuClick: () -> Unit,
    onNewClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSubtitleClick: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    onEditorClick: (() -> Unit)? = null,
    showMenuButton: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMenuButton) {
            Box(modifier = Modifier.size(44.dp).clickable(onClick = onMenuClick), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Menu, contentDescription = "Menú", tint = MaterialTheme.colorScheme.onBackground)
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (onSubtitleClick != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onSearchClick != null) {
            Box(modifier = Modifier.size(44.dp).clickable(onClick = onSearchClick), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (onEditorClick != null) {
            Box(modifier = Modifier.size(44.dp).clickable(onClick = onEditorClick), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.EditNote, contentDescription = "Editor", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Box(modifier = Modifier.size(44.dp).clickable(onClick = onNewClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = "Nuevo", tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}
