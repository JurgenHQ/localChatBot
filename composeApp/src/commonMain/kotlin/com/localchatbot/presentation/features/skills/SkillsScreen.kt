package com.localchatbot.presentation.features.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.fs.rememberDirectoryPicker
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SectionLabel
import com.localchatbot.presentation.components.atoms.SecondaryButton
import com.localchatbot.presentation.components.molecules.SectionCard

@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SkillsContent(
            state = state,
            onClose = onClose,
            onOpenDetail = viewModel::openDetail,
            onInstall = viewModel::install,
            onToggleEnabled = viewModel::toggleEnabled,
            onOpenCreate = viewModel::openCreateSheet,
            onOpenImportExport = viewModel::openImportExport
        )

        state.detailItem?.let { item ->
            SkillDetailSheet(
                item = item,
                onDismiss = viewModel::closeDetail,
                onInstall = { viewModel.install(item.definition.id) },
                onUninstall = { viewModel.uninstall(item.definition.id); viewModel.closeDetail() },
                onToggleEnabled = { viewModel.toggleEnabled(item.definition.id, it) },
                onEdit = if (item.isCustom) {
                    { viewModel.openEditSheet(item.definition); viewModel.closeDetail() }
                } else null,
                onDelete = if (item.isCustom) {
                    { viewModel.deleteCustomSkill(item.definition.id) }
                } else null
            )
        }

        if (state.showCreateSheet) {
            SkillCreateSheet(
                editingSkill = state.editingSkill,
                onDismiss = viewModel::closeCreateSheet,
                onSave = { name, desc, fullDesc, prompt, scripts ->
                    viewModel.saveCustomSkill(name, desc, fullDesc, prompt, scripts)
                }
            )
        }

        if (state.showImportExport) {
            ImportExportSheet(
                exportJson = state.exportJson,
                importError = state.importError,
                folderImportResult = state.folderImportResult,
                onImportMarkdown = viewModel::importMarkdown,
                onImportJson = viewModel::importJson,
                onImportFromFolder = viewModel::importFromFolder,
                onClearFolderResult = viewModel::clearFolderImportResult,
                onDismiss = viewModel::closeImportExport
            )
        }
    }
}

@Composable
private fun SkillsContent(
    state: SkillsUiState,
    onClose: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onInstall: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onOpenCreate: () -> Unit,
    onOpenImportExport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Volver",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "Skills",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenCreate) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Crear skill",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Text(
            "Los skills amplían el comportamiento del agente con instrucciones especializadas. El modelo los carga bajo demanda.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val installedBuiltIn = state.builtInItems.filter { it.isInstalled }
        val availableBuiltIn = state.builtInItems.filter { !it.isInstalled }

        if (state.customItems.isNotEmpty()) {
            SectionLabel("Mis skills")
            SkillSection(
                items = state.customItems,
                onOpenDetail = onOpenDetail,
                onToggleEnabled = onToggleEnabled
            )
        }

        if (installedBuiltIn.isNotEmpty()) {
            SectionLabel("Instalados")
            SkillSection(
                items = installedBuiltIn,
                onOpenDetail = onOpenDetail,
                onToggleEnabled = onToggleEnabled
            )
        }

        if (availableBuiltIn.isNotEmpty()) {
            SectionLabel("Disponibles")
            SkillSection(
                items = availableBuiltIn,
                onOpenDetail = onOpenDetail,
                onToggleEnabled = { _, _ -> }
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(
            text = "Importar / Exportar skills",
            onClick = onOpenImportExport,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private const val SKILL_SECTION_MAX_VISIBLE = 4
private val SKILL_ROW_HEIGHT = 72.dp

@Composable
private fun SkillSection(
    items: List<SkillUiItem>,
    onOpenDetail: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit
) {
    SectionCard {
        val needsScroll = items.size > SKILL_SECTION_MAX_VISIBLE
        val columnModifier = if (needsScroll) {
            Modifier
                .heightIn(max = SKILL_ROW_HEIGHT * SKILL_SECTION_MAX_VISIBLE)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        Column(modifier = columnModifier) {
            items.forEachIndexed { index, item ->
                SkillRow(
                    item = item,
                    onClick = { onOpenDetail(item.definition.id) },
                    onToggleEnabled = { onToggleEnabled(item.definition.id, it) }
                )
                if (index < items.lastIndex) Divider()
            }
        }
    }
}

@Composable
private fun SkillRow(
    item: SkillUiItem,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(item.definition.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (item.isCustom) {
                    Text(
                        "propio",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Text(item.definition.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.isInstalled) {
            Switch(checked = item.isEnabled, onCheckedChange = onToggleEnabled)
        }
    }
}

@Composable
private fun SkillDetailSheet(
    item: SkillUiItem,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(item.definition.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(item.definition.fullDescription.ifBlank { item.definition.description }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (item.isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Habilitado", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = item.isEnabled, onCheckedChange = onToggleEnabled)
                }
                if (onEdit != null) {
                    SecondaryButton(text = "Editar", onClick = onEdit, modifier = Modifier.fillMaxWidth())
                }
                SecondaryButton(text = "Desinstalar", onClick = onUninstall, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(text = "Instalar", onClick = { onInstall(); onDismiss() }, modifier = Modifier.fillMaxWidth())
            }

            if (onDelete != null) {
                Text(
                    "Eliminar skill",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDelete(); onDismiss() }
                        .padding(vertical = Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun ImportExportSheet(
    exportJson: String,
    importError: String?,
    folderImportResult: String?,
    onImportMarkdown: (String) -> Unit,
    onImportJson: (String) -> Unit,
    onImportFromFolder: (String) -> Unit,
    onClearFolderResult: () -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(ImportExportMode.Markdown) }
    var markdownText by remember { mutableStateOf("") }
    var jsonText by remember { mutableStateOf("") }

    // Composable-level: picker debe instanciarse aquí, no dentro del lambda de click.
    val folderPicker = if (PlatformCapabilities.isDesktop) {
        rememberDirectoryPicker { path ->
            onClearFolderResult()
            onImportFromFolder(path)
        }
    } else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text("Importar / Exportar", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ModeTab("Markdown", mode == ImportExportMode.Markdown) { mode = ImportExportMode.Markdown }
                ModeTab("JSON", mode == ImportExportMode.Json) { mode = ImportExportMode.Json }
            }

            when (mode) {
                ImportExportMode.Markdown -> {
                    Text(
                        "Pega un archivo SKILL.md (con o sin frontmatter):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AppTextField(
                        value = markdownText,
                        onValueChange = { markdownText = it },
                        placeholder = "---\nname: Mi Skill\ndescription: ...\n---\nInstrucciones del sistema...",
                        singleLine = false,
                        monospace = true
                    )
                    if (importError != null) {
                        Text(importError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    PrimaryButton(
                        text = "Importar",
                        enabled = markdownText.isNotBlank(),
                        onClick = { onImportMarkdown(markdownText) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (folderPicker != null) {
                        SecondaryButton(
                            text = "Importar desde carpeta…",
                            onClick = { onClearFolderResult(); folderPicker.launch() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (folderImportResult != null) {
                            Text(
                                folderImportResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ImportExportMode.Json -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ModeTab("Exportar", jsonText.isBlank() && exportJson.isNotBlank()) {}
                        ModeTab("Importar", true) {}
                    }
                    if (exportJson.isNotBlank()) {
                        Text("Copia el JSON y compártelo:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppTextField(value = exportJson, onValueChange = {}, singleLine = false, monospace = true)
                    }
                    Text("Pega el JSON de skills aquí:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        placeholder = """{"version":1,"skills":[...]}""",
                        singleLine = false,
                        monospace = true
                    )
                    if (importError != null) {
                        Text(importError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    PrimaryButton(
                        text = "Importar JSON",
                        enabled = jsonText.isNotBlank(),
                        onClick = { onImportJson(jsonText) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SecondaryButton(text = "Cerrar", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

private enum class ImportExportMode { Markdown, Json }

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}
