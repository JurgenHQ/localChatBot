package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
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
 * Barra compacta con los controles del agente: workspace, rama, modo Plan/Build, sandbox,
 * YOLO, preview de ediciones y terminal. Los chips **envuelven a varias líneas** cuando no
 * caben a lo ancho, en vez de salirse de la vista.
 *
 * Solo visible en desktop (la decisión de mostrarla la toma quien la consume,
 * típicamente [com.localchatbot.presentation.features.chat.ChatScreen]). Los
 * estados se reflejan visualmente:
 * - **Workspace**: muestra la última parte del path o "Sin workspace" si no hay.
 * - **Sandbox**: chip "activo" cuando los paths están restringidos al workspace,
 *   chip "ghost" cuando se permite acceso fuera (más peligroso).
 * - **YOLO**: chip "activo" cuando se omiten las confirmaciones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentControlsBar(
    workspaceDir: String?,
    gitBranch: String?,
    sandboxOn: Boolean,
    yoloOn: Boolean,
    previewEditsOn: Boolean,
    planMode: Boolean,
    terminalOpen: Boolean,
    onPickWorkspace: () -> Unit,
    onOpenWorkspaceFolder: () -> Unit,
    onToggleSandbox: () -> Unit,
    onToggleYolo: () -> Unit,
    onTogglePreviewEdits: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleTerminal: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // FlowRow y no un Row con scroll horizontal: con scroll, los chips que no caben quedan
    // fuera de la vista y sin ninguna pista de que están ahí — en una ventana estrecha
    // parecía que faltaban opciones. Aquí bajan a la línea siguiente y se ven todos.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
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
        // Abre/cierra el panel de terminal. Solo icono: el símbolo es inequívoco y la barra
        // ya va justa de ancho. Se resalta cuando está abierto porque el panel vive fuera
        // del chat y desde aquí no siempre se ve (con el chat scrolleado).
        if (onToggleTerminal != null) {
            AgentChip(
                icon = Icons.Filled.Terminal,
                label = null,
                active = terminalOpen,
                onClick = onToggleTerminal,
                contentDescription = if (terminalOpen) "Cerrar la terminal" else "Abrir la terminal"
            )
        }
        // Abrir la carpeta en el explorador del sistema. Va como icono aparte, al final de
        // la barra, y no como acción del chip de workspace porque ese chip ya sirve para
        // *cambiar* de workspace: mezclar ambas cosas en un mismo click obligaría a elegir
        // cuál se pierde.
        if (workspaceDir != null) {
            AgentChip(
                icon = Icons.Outlined.FolderOpen,
                label = null,
                active = false,
                onClick = onOpenWorkspaceFolder,
                contentDescription = "Abrir la carpeta en el explorador de archivos"
            )
        }
    }
}

@Composable
private fun AgentChip(
    icon: ImageVector,
    /** Null = chip de solo icono; entonces [contentDescription] es lo que lo nombra. */
    label: String?,
    active: Boolean,
    // Null = chip puramente informativo (p.ej. la rama git): sin `clickable` para que no
    // muestre ripple ni parezca accionable.
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null
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
            // Sin etiqueta, el padding horizontal baja al vertical para que el chip quede
            // cuadrado en vez de una cápsula con un icono perdido en el centro.
            .padding(horizontal = if (label == null) Spacing.sm else Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(16.dp))
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Techo de ancho para que un path o una rama largos no hagan un chip más
                // ancho que la propia fila, que ni envolviendo cabría.
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
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
