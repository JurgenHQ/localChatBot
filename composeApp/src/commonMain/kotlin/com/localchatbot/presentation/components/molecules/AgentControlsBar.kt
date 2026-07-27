package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing

/**
 * Barra compacta con los 3 controles del agente: workspace, sandbox y YOLO.
 *
 * Solo visible en desktop (la decisión de mostrarla la toma quien la consume,
 * típicamente [com.localchatbot.presentation.features.chat.ChatScreen]). Los
 * estados se reflejan visualmente:
 * - **Workspace**: muestra la última parte del path o "Sin workspace" si no hay.
 * - **Sandbox**: chip "activo" cuando los paths están restringidos al workspace,
 *   chip "ghost" cuando se permite acceso fuera (más peligroso).
 * - **YOLO**: chip "activo" cuando se omiten las confirmaciones.
 */
@Composable
fun AgentControlsBar(
    workspaceDir: String?,
    gitBranch: String?,
    sandboxOn: Boolean,
    yoloOn: Boolean,
    previewEditsOn: Boolean,
    planMode: Boolean,
    onPickWorkspace: () -> Unit,
    onOpenWorkspaceFolder: () -> Unit,
    onToggleSandbox: () -> Unit,
    onToggleYolo: () -> Unit,
    onTogglePreviewEdits: () -> Unit,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AgentChip(
            icon = Icons.Filled.Folder,
            label = workspaceDir?.shortenPath() ?: "Sin workspace",
            active = workspaceDir != null,
            onClick = onPickWorkspace
        )
        // Solo aparece si el workspace es un repo git; no es clickeable, es informativo.
        if (gitBranch != null) {
            AgentChip(
                icon = Icons.Outlined.AccountTree,
                label = gitBranch,
                active = false
            )
        }
        // Modo Plan (solo lectura) / Build (puede escribir). Plan se resalta para dejar
        // claro que el agente no puede modificar archivos.
        AgentChip(
            icon = if (planMode) Icons.Filled.Visibility else Icons.Filled.Build,
            label = if (planMode) "Plan" else "Build",
            active = planMode,
            onClick = onToggleMode
        )
        AgentChip(
            icon = if (sandboxOn) Icons.Filled.Lock else Icons.Filled.LockOpen,
            label = if (sandboxOn) "Sandbox" else "Sin sandbox",
            active = sandboxOn,
            onClick = onToggleSandbox
        )
        AgentChip(
            icon = Icons.Filled.Bolt,
            label = "YOLO",
            active = yoloOn,
            onClick = onToggleYolo
        )
        AgentChip(
            icon = Icons.Filled.Difference,
            label = "Preview edits",
            active = previewEditsOn,
            onClick = onTogglePreviewEdits
        )
        // Abrir la carpeta en el explorador del sistema. Va como icono aparte, al final de
        // la barra, y no como acción del chip de workspace porque ese chip ya sirve para
        // *cambiar* de workspace: mezclar ambas cosas en un mismo click obligaría a elegir
        // cuál se pierde.
        if (workspaceDir != null) {
            OpenFolderButton(onClick = onOpenWorkspaceFolder)
        }
    }
}

@Composable
private fun AgentChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    // Null = chip puramente informativo (p.ej. la rama git): sin `clickable` para que no
    // muestre ripple ni parezca accionable.
    onClick: (() -> Unit)? = null
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.sm))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Botón cuadrado y compacto (mismo alto que los chips) que abre el workspace en el
 * explorador del sistema. Sin etiqueta: la carpeta ya se nombra en el chip de al lado.
 */
@Composable
private fun OpenFolderButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.FolderOpen,
            contentDescription = "Abrir la carpeta en el explorador de archivos",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Acorta un path largo dejando solo las 2 últimas componentes. */
private fun String.shortenPath(): String {
    val parts = this.split('/', '\\').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> this
        parts.size <= 2 -> parts.joinToString("/")
        else -> "…/" + parts.takeLast(2).joinToString("/")
    }
}
