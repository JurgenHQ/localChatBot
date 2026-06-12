package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class BottomTab { Chat, Agent, Settings }

@Composable
fun AppBottomBar(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabItem(
            label = "Chat",
            iconContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    tint = it,
                    modifier = Modifier.size(22.dp)
                )
            },
            selected = selected == BottomTab.Chat,
            onClick = { onSelect(BottomTab.Chat) }
        )
        TabItem(
            label = "Agente",
            iconContent = {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = it,
                    modifier = Modifier.size(22.dp)
                )
            },
            selected = selected == BottomTab.Agent,
            onClick = { onSelect(BottomTab.Agent) }
        )
        TabItem(
            label = "Configuración",
            iconContent = {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = it,
                    modifier = Modifier.size(22.dp)
                )
            },
            selected = selected == BottomTab.Settings,
            onClick = { onSelect(BottomTab.Settings) }
        )
    }
}

@Composable
private fun TabItem(
    label: String,
    iconContent: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        iconContent(color)
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
