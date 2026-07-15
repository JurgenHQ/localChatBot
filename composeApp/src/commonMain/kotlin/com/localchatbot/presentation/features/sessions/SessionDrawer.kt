package com.localchatbot.presentation.features.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.repository.ProjectRepository.Companion.AUTOMATION_GROUP_ID
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
    onNewSession: () -> Unit = {},
    onOpenTasks: (() -> Unit)? = null,
    showScrim: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionDrawerContent(
        ungrouped = state.ungrouped,
        groups = state.groups,
        automationSessions = state.automationSessions,
        projectsEnabled = state.projectsEnabled,
        query = state.query,
        connectionLabel = state.connectionLabel,
        connectionProfiles = state.connectionProfiles,
        activeConnectionProfileId = state.activeConnectionProfileId,
        onSwitchProfile = viewModel::switchConnectionProfile,
        onQueryChange = viewModel::onQueryChange,
        onSelect = viewModel::selectSession,
        onDelete = viewModel::deleteSession,
        onNew = {
            viewModel.newSession()
            onNewSession()
        },
        onNewInProject = { projectId ->
            viewModel.newSessionInProject(projectId)
            onNewSession()
        },
        onRename = viewModel::renameSession,
        onTogglePin = viewModel::togglePinned,
        onMoveSession = viewModel::moveSessionToProject,
        onCreateProject = viewModel::createProject,
        onRenameProject = viewModel::renameProject,
        onChangeProjectWorkspace = viewModel::updateProjectWorkspace,
        onDeleteProject = viewModel::deleteProject,
        onToggleCollapsed = viewModel::toggleProjectCollapsed,
        onOpenTasks = onOpenTasks?.let {
            {
                viewModel.closeDrawer()
                it()
            }
        },
        onDismiss = viewModel::closeDrawer,
        showScrim = showScrim,
        modifier = modifier
    )
}

@Composable
fun SessionDrawerContent(
    ungrouped: List<ChatSession>,
    query: String,
    connectionLabel: String,
    connectionProfiles: List<com.localchatbot.domain.model.ConnectionProfile> = emptyList(),
    activeConnectionProfileId: String = "",
    onSwitchProfile: (String) -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
    groups: List<ProjectGroup> = emptyList(),
    automationSessions: List<ChatSession> = emptyList(),
    projectsEnabled: Boolean = false,
    onNewInProject: (String) -> Unit = {},
    onMoveSession: (String, String?) -> Unit = { _, _ -> },
    onCreateProject: (String, String) -> Unit = { _, _ -> },
    onRenameProject: (String, String) -> Unit = { _, _ -> },
    onChangeProjectWorkspace: (String, String) -> Unit = { _, _ -> },
    onDeleteProject: (String) -> Unit = {},
    onToggleCollapsed: (String) -> Unit = {},
    onOpenTasks: (() -> Unit)? = null,
    onRename: (String, String) -> Unit = { _, _ -> },
    onTogglePin: (String) -> Unit = {},
    showScrim: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Diálogos de proyecto (solo relevantes en Desktop).
    var showCreateProject by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<ChatSession?>(null) }
    // Sección "Tareas automatizadas": colapsada por defecto (se van acumulando).
    var automationCollapsed by remember { mutableStateOf(true) }

    val hasProjects = groups.isNotEmpty()
    // Callback "mover a proyecto" solo si hay a dónde mover (algún proyecto existe).
    val moveCallback: ((ChatSession) -> (() -> Unit)?) = { session ->
        if (projectsEnabled && hasProjects) ({ moveTarget = session }) else null
    }

    // Drag & drop: arrastrar una sesión sobre la cabecera de un proyecto (o "Sin proyecto")
    // la reasigna. dropBounds mapea el target (projectId, null = sin proyecto) → su rango
    // vertical en coordenadas de root; drag lleva la sesión arrastrada y la Y del puntero.
    val dragEnabled = projectsEnabled && hasProjects
    val dropBounds = remember { mutableStateMapOf<String?, ClosedFloatingPointRange<Float>>() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val hoveredKey: String? = drag?.let { d -> dropBounds.entries.firstOrNull { d.pointerY in it.value }?.key }
    val hovering: Boolean = drag != null && dropBounds.entries.any { drag!!.pointerY in it.value }
    val endDrag = {
        val d = drag
        if (d != null) {
            val hit = dropBounds.entries.firstOrNull { d.pointerY in it.value }
            if (hit != null && hit.key != d.fromProjectId) onMoveSession(d.sessionId, hit.key)
        }
        drag = null
    }

    val rowModifier = if (showScrim) modifier.fillMaxSize() else modifier.fillMaxHeight()
    Row(modifier = rowModifier) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            var profileMenuOpen by remember { mutableStateOf(false) }
            val canSwitchProfile = connectionProfiles.size > 1
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (canSwitchProfile) {
                        Modifier.clickable { profileMenuOpen = true }
                    } else Modifier
                ) {
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
                if (canSwitchProfile) {
                    DropdownMenu(expanded = profileMenuOpen, onDismissRequest = { profileMenuOpen = false }) {
                        connectionProfiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name) },
                                leadingIcon = if (profile.id == activeConnectionProfileId) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    profileMenuOpen = false
                                    onSwitchProfile(profile.id)
                                }
                            )
                        }
                    }
                }
            }

            AppTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "  Buscar conversación"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedActionRow(
                    icon = Icons.Default.Add,
                    label = "Nueva conversación",
                    onClick = onNew,
                    modifier = Modifier.weight(1f)
                )
                if (projectsEnabled) {
                    NewProjectIconButton(onClick = { showCreateProject = true })
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // Proyectos primero (secciones colapsables), luego "Sin proyecto".
                groups.forEach { group ->
                    item(key = "project-${group.project.id}") {
                        ProjectHeader(
                            name = group.project.name,
                            workspaceDir = group.project.workspaceDir,
                            collapsed = group.project.collapsed,
                            count = group.sessions.size,
                            isDropTarget = hovering && hoveredKey == group.project.id,
                            onBounds = { dropBounds[group.project.id] = it },
                            onToggle = { onToggleCollapsed(group.project.id) },
                            onNewSession = { onNewInProject(group.project.id) },
                            onRename = { onRenameProject(group.project.id, it) },
                            onChangeWorkspace = { onChangeProjectWorkspace(group.project.id, it) },
                            onDelete = { onDeleteProject(group.project.id) }
                        )
                    }
                    if (!group.project.collapsed) {
                        items(group.sessions, key = { it.id }) { session ->
                            DraggableSession(
                                session = session,
                                dragEnabled = dragEnabled,
                                dragging = drag?.sessionId == session.id,
                                dragOffsetY = drag?.takeIf { it.sessionId == session.id }?.let { it.pointerY - it.startY } ?: 0f,
                                onDragStart = { y -> drag = DragState(session.id, group.project.id, y, y) },
                                onDragMove = { y -> drag = drag?.copy(pointerY = y) },
                                onDragEnd = endDrag,
                                onClick = { onSelect(session.id) },
                                onDelete = { onDelete(session.id) },
                                onRename = { newTitle -> onRename(session.id, newTitle) },
                                onTogglePin = { onTogglePin(session.id) },
                                onMoveToProject = moveCallback(session)
                            )
                        }
                    }
                }

                if ((hasProjects || automationSessions.isNotEmpty()) && ungrouped.isNotEmpty()) {
                    item(key = "ungrouped-header") {
                        SectionLabel(
                            text = "Sin proyecto",
                            isDropTarget = hovering && hoveredKey == null,
                            onBounds = { dropBounds[null] = it }
                        )
                    }
                }
                items(ungrouped, key = { it.id }) { session ->
                    DraggableSession(
                        session = session,
                        dragEnabled = dragEnabled,
                        dragging = drag?.sessionId == session.id,
                        dragOffsetY = drag?.takeIf { it.sessionId == session.id }?.let { it.pointerY - it.startY } ?: 0f,
                        onDragStart = { y -> drag = DragState(session.id, null, y, y) },
                        onDragMove = { y -> drag = drag?.copy(pointerY = y) },
                        onDragEnd = endDrag,
                        onClick = { onSelect(session.id) },
                        onDelete = { onDelete(session.id) },
                        onRename = { newTitle -> onRename(session.id, newTitle) },
                        onTogglePin = { onTogglePin(session.id) },
                        onMoveToProject = moveCallback(session)
                    )
                }

                // Sección "Tareas automatizadas": las sesiones que generan las tareas al
                // ejecutarse. Solo aparece si hay alguna; colapsable, sin acciones de edición.
                if (automationSessions.isNotEmpty()) {
                    item(key = "automation-header") {
                        AutomationSectionHeader(
                            count = automationSessions.size,
                            collapsed = automationCollapsed,
                            onToggle = { automationCollapsed = !automationCollapsed }
                        )
                    }
                    if (!automationCollapsed) {
                        items(automationSessions, key = { it.id }) { session ->
                            DraggableSession(
                                session = session,
                                dragEnabled = dragEnabled,
                                dragging = drag?.sessionId == session.id,
                                dragOffsetY = drag?.takeIf { it.sessionId == session.id }?.let { it.pointerY - it.startY } ?: 0f,
                                onDragStart = { y -> drag = DragState(session.id, AUTOMATION_GROUP_ID, y, y) },
                                onDragMove = { y -> drag = drag?.copy(pointerY = y) },
                                onDragEnd = endDrag,
                                onClick = { onSelect(session.id) },
                                onDelete = { onDelete(session.id) },
                                onRename = { newTitle -> onRename(session.id, newTitle) },
                                onTogglePin = { onTogglePin(session.id) },
                                onMoveToProject = moveCallback(session)
                            )
                        }
                    }
                }
            }

            // Tareas automatizadas: solo desktop (el scheduler necesita la app abierta
            // y las tools locales/MCP). En móvil no se muestra la entrada.
            if (onOpenTasks != null && PlatformCapabilities.isDesktop) {
                DrawerNavRow(
                    icon = Icons.Outlined.DateRange,
                    label = "Tareas",
                    onClick = onOpenTasks
                )
            }
        }

        if (showScrim) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.35f))
                    // pointerInput, no clickable: evita que Espacio (semántica de
                    // teclado de clickable en desktop) cierre el drawer con foco.
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }
    }

    if (showCreateProject) {
        CreateProjectDialog(
            onConfirm = { name, dir ->
                showCreateProject = false
                onCreateProject(name, dir)
            },
            onDismiss = { showCreateProject = false }
        )
    }

    moveTarget?.let { session ->
        MoveToProjectDialog(
            projects = groups.map { it.project.id to it.project.name },
            currentProjectId = groups.firstOrNull { g -> g.sessions.any { it.id == session.id } }?.project?.id,
            onSelect = { projectId ->
                moveTarget = null
                onMoveSession(session.id, projectId)
            },
            onDismiss = { moveTarget = null }
        )
    }
}

@Composable
private fun OutlinedActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Botón compacto (cuadrado) para crear un proyecto, junto a "Nueva conversación". */
@Composable
private fun NewProjectIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = "Nuevo proyecto",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SectionLabel(
    text: String,
    isDropTarget: Boolean = false,
    onBounds: ((ClosedFloatingPointRange<Float>) -> Unit)? = null
) {
    val bg = if (isDropTarget) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isDropTarget) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned {
                        val r = it.boundsInRoot(); onBounds(r.top..r.bottom)
                    }
                } else Modifier
            )
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .padding(top = Spacing.sm, bottom = Spacing.xs)
    )
}

/** Cabecera colapsable de la sección "Tareas automatizadas" (sin acciones de edición). */
@Composable
private fun AutomationSectionHeader(count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = if (collapsed) "Expandir" else "Colapsar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            Icons.Outlined.DateRange,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            "Tareas automatizadas ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Estado de un drag en curso: qué sesión, de qué proyecto salió, la Y inicial y la Y actual del
 * puntero (en coordenadas de root). El desplazamiento visual de la fila es `pointerY - startY`.
 */
private data class DragState(
    val sessionId: String,
    val fromProjectId: String?,
    val startY: Float,
    val pointerY: Float
)

/**
 * Envuelve [SessionRow] añadiendo arrastre: con long-press se levanta la sesión y al soltarla
 * sobre la cabecera de un proyecto (o "Sin proyecto") se reasigna. El seguimiento de la Y y el
 * hit-testing contra las cabeceras vive en el llamador (dropBounds); aquí solo emitimos eventos.
 */
@Composable
private fun DraggableSession(
    session: ChatSession,
    dragEnabled: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    onDragStart: (Float) -> Unit,
    onDragMove: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePin: () -> Unit,
    onMoveToProject: (() -> Unit)?
) {
    val row: @Composable (Modifier) -> Unit = { m ->
        SessionRow(
            session = session,
            onClick = onClick,
            onDelete = onDelete,
            onRename = onRename,
            onTogglePin = onTogglePin,
            onMoveToProject = onMoveToProject,
            modifier = m
        )
    }

    if (!dragEnabled) {
        row(Modifier)
        return
    }

    // La fila sigue al cursor mientras se arrastra (translationY: no afecta el layout de las
    // demás filas) y se dibuja por encima con algo de transparencia.
    val containerModifier = if (dragging) {
        Modifier.zIndex(1f).graphicsLayer { translationY = dragOffsetY; alpha = 0.9f }
    } else Modifier

    // Fila = contenido (con swipe-para-borrar) + asa de agarre a la derecha. El asa está FUERA
    // del área de swipe y captura el arrastre con su propio pointerInput (detectDragGestures
    // inmediato), por lo que no pelea con el swipe del cuerpo.
    var handleTop by remember { mutableStateOf(0f) }
    Row(modifier = containerModifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { row(Modifier) }
        Box(
            modifier = Modifier
                .onGloballyPositioned { handleTop = it.positionInRoot().y }
                .size(width = 28.dp, height = 44.dp)
                .pointerInput(session.id) {
                    // Y absoluta acumulada: se ancla en handleTop (posición sin trasladar) al
                    // empezar y luego se suma el delta de cada frame. Evita releer positionInRoot
                    // durante el drag, que estaría contaminado por la traslación visual.
                    var pointerY = 0f
                    detectDragGestures(
                        onDragStart = { offset -> pointerY = handleTop + offset.y; onDragStart(pointerY) },
                        onDrag = { change, amount -> change.consume(); pointerY += amount.y; onDragMove(pointerY) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            GripHandle()
        }
    }
}

/** Asa de agarre (dos columnas de tres puntos) para arrastrar la sesión a un proyecto. */
@Composable
private fun GripHandle() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectHeader(
    name: String,
    workspaceDir: String,
    collapsed: Boolean,
    count: Int,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onRename: (String) -> Unit,
    onChangeWorkspace: (String) -> Unit,
    onDelete: () -> Unit,
    isDropTarget: Boolean = false,
    onBounds: ((ClosedFloatingPointRange<Float>) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val folderPicker = rememberDirectoryPicker(onResult = onChangeWorkspace)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned {
                        val r = it.boundsInRoot(); onBounds(r.top..r.bottom)
                    }
                } else Modifier
            )
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (isDropTarget) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = if (collapsed) "Expandir" else "Colapsar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$name ($count)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                workspaceDir.trimEnd('/').substringAfterLast('/').ifBlank { workspaceDir },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onNewSession, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Nueva conversación en el proyecto",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Opciones del proyecto",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    onClick = { menuOpen = false; renameOpen = true }
                )
                DropdownMenuItem(
                    text = { Text("Cambiar carpeta") },
                    onClick = { menuOpen = false; folderPicker.launch() }
                )
                DropdownMenuItem(
                    text = { Text("Borrar proyecto") },
                    onClick = { menuOpen = false; deleteOpen = true }
                )
            }
        }
    }

    if (renameOpen) {
        TextInputDialog(
            title = "Renombrar proyecto",
            initial = name,
            confirmLabel = "Guardar",
            onConfirm = { renameOpen = false; onRename(it) },
            onDismiss = { renameOpen = false }
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Borrar proyecto") },
            text = { Text("Se eliminará el proyecto \"$name\". Sus conversaciones no se borran: quedarán en \"Sin proyecto\".") },
            confirmButton = {
                TextButton(onClick = { deleteOpen = false; onDelete() }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onConfirm: (name: String, workspaceDir: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    val picker = rememberDirectoryPicker(onResult = { dir = it })
    val valid = name.isNotBlank() && dir.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo proyecto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nombre") }
                )
                OutlinedActionRow(
                    icon = Icons.Default.Folder,
                    label = if (dir.isBlank()) "Elegir carpeta de workspace" else dir,
                    onClick = { picker.launch() }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name, dir) }) { Text("Crear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun MoveToProjectDialog(
    projects: List<Pair<String, String>>,
    currentProjectId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover a proyecto") },
        text = {
            Column {
                DropdownMenuItem(
                    text = { Text("Sin proyecto") },
                    onClick = { onSelect(null) },
                    trailingIcon = { if (currentProjectId == null) SelectedDot() }
                )
                projects.forEach { (id, projectName) ->
                    DropdownMenuItem(
                        text = { Text(projectName) },
                        onClick = { onSelect(id) },
                        trailingIcon = { if (currentProjectId == id) SelectedDot() }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun SelectedDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Preview
@Composable
private fun SessionDrawerPreview() = PreviewSurface {
    SessionDrawerContent(
        ungrouped = PreviewData.sessionList,
        query = "",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun SessionDrawerWithSearchPreview() = PreviewSurface {
    SessionDrawerContent(
        ungrouped = PreviewData.sessionList.filter { it.title.contains("auth", ignoreCase = true) },
        query = "auth",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun SessionDrawerEmptyPreview() = PreviewSurface {
    SessionDrawerContent(
        ungrouped = emptyList(),
        query = "",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun SessionDrawerDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    SessionDrawerContent(
        ungrouped = PreviewData.sessionList,
        query = "",
        connectionLabel = "192.168.1.42:1234",
        onQueryChange = {}, onSelect = {}, onDelete = {},
        onNew = {}, onDismiss = {}
    )
}
