package com.localchatbot.presentation.features.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.domain.model.McpTransportConfig
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SecondaryButton

@Composable
fun McpServerEditSheet(
    editing: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    val isHttp = editing?.transport is McpTransportConfig.Http
    var transportMode by remember { mutableStateOf(if (isHttp) "http" else "stdio") }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var command by remember {
        mutableStateOf((editing?.transport as? McpTransportConfig.Stdio)?.command ?: "")
    }
    var argsText by remember {
        mutableStateOf((editing?.transport as? McpTransportConfig.Stdio)?.args?.joinToString(" ") ?: "")
    }
    var url by remember {
        mutableStateOf((editing?.transport as? McpTransportConfig.Http)?.url ?: "")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false, onClick = {})
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                if (editing == null) "Agregar servidor MCP" else "Editar servidor MCP",
                style = MaterialTheme.typography.titleLarge
            )

            // Transport mode selector
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf("stdio", "http").forEach { mode ->
                    val selected = transportMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { transportMode = mode }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    ) {
                        Text(
                            mode.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Nombre (ej. GitHub MCP)"
            )

            if (transportMode == "stdio") {
                AppTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = "Comando (ej. npx, uvx, /usr/bin/mcp-server)"
                )
                AppTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    placeholder = "Argumentos separados por espacios (opcional)"
                )
                Text(
                    "El proceso se lanza en desktop. No disponible en Android/iOS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "URL del servidor MCP (ej. https://mcp.example.com/)"
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(
                    text = "Cancelar",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                val canSave = (transportMode == "stdio" && command.isNotBlank()) ||
                    (transportMode == "http" && url.isNotBlank())
                PrimaryButton(
                    text = "Guardar",
                    onClick = {
                        val transport = if (transportMode == "stdio") {
                            val args = argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            McpTransportConfig.Stdio(command = command.trim(), args = args)
                        } else {
                            McpTransportConfig.Http(url = url.trim())
                        }
                        val resolvedName = name.trim().ifBlank {
                            when (val t = transport) {
                                is McpTransportConfig.Stdio -> t.command.substringAfterLast('/').substringAfterLast('\\')
                                is McpTransportConfig.Http -> t.url
                            }
                        }
                        onSave(
                            McpServerConfig(
                                id = editing?.id ?: "mcp_${newId()}",
                                name = resolvedName,
                                transport = transport,
                                enabled = editing?.enabled ?: true
                            )
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
