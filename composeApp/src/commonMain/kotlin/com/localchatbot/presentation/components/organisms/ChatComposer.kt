package com.localchatbot.presentation.components.organisms

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.localchatbot.core.fs.AttachedTextFile
import com.localchatbot.core.image.decodeImage
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.presentation.components.atoms.ChatInputField
import com.localchatbot.presentation.components.atoms.IconSquareButton
import com.localchatbot.presentation.components.atoms.SendIconButton
import com.localchatbot.presentation.components.molecules.SlashSuggestionPopup
import com.localchatbot.presentation.features.chat.SlashCommand

@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    sending: Boolean = false,
    attachedImageBytes: ByteArray? = null,
    onRemoveAttachment: () -> Unit = {},
    attachedTextFiles: List<AttachedTextFile> = emptyList(),
    onAttachTextFile: (() -> Unit)? = null,
    onRemoveTextFile: ((String) -> Unit)? = null,
    onVoice: () -> Unit = {},
    onStop: () -> Unit = {},
    onTemplates: (() -> Unit)? = null,
    voiceSupported: Boolean = true,
    onPasteImage: ((ByteArray) -> Unit)? = null,
    pendingSkill: SkillDefinition? = null,
    installedSkills: List<SkillDefinition> = emptyList(),
    onSelectSkill: (SkillDefinition) -> Unit = {},
    /** Comandos `/` ofrecibles en el estado actual (ver [SlashCommand.availableFor]). */
    slashCommands: List<SlashCommand> = emptyList(),
    onSelectCommand: (SlashCommand) -> Unit = {},
    onClearSkill: () -> Unit = {},
    /** Pregunta pendiente del modelo (tool `ask_user`); null si no hay. */
    pendingPrompt: com.localchatbot.core.state.PendingUserPrompt? = null,
    onSelectPromptOption: (String) -> Unit = {},
    /**
     * Slot opcional renderizado debajo de la fila del input (después del botón
     * de adjuntar imagen y de plantillas). Se usa para los chips del agente
     * (workspace / sandbox / YOLO) cuando aplica.
     */
    agentBar: (@Composable () -> Unit)? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissAndSend: () -> Unit = {
        keyboard?.hide()
        onSend()
    }

    // Con solo "/" se abre aunque no haya skills instaladas: los comandos siempre están.
    val showSlashPopup = value.startsWith("/") &&
        (installedSkills.isNotEmpty() || slashCommands.isNotEmpty())
    // Solo el primer token tras "/" filtra el popup; el resto es el argumento.
    val slashQuery = if (value.startsWith("/")) value.drop(1).substringBefore(' ') else ""

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        if (showSlashPopup) {
            SlashSuggestionPopup(
                query = slashQuery,
                commands = slashCommands,
                skills = installedSkills,
                onSelectCommand = { cmd ->
                    // El comando se ejecuta y el composer queda limpio: no es un mensaje.
                    onValueChange("")
                    onSelectCommand(cmd)
                },
                onSelectSkill = { skill ->
                    onSelectSkill(skill)
                    // El texto tras el primer espacio queda como mensaje del usuario.
                    val remainder = value.drop(1).substringAfter(' ', "").trim()
                    onValueChange(remainder)
                },
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
        }
        if (pendingSkill != null) {
            SkillBadge(
                skill = pendingSkill,
                onClear = onClearSkill,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
        }
        if (attachedImageBytes != null) {
            AttachmentPreview(
                bytes = attachedImageBytes,
                onRemove = onRemoveAttachment,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
        if (attachedTextFiles.isNotEmpty()) {
            TextFileChips(
                files = attachedTextFiles,
                onRemove = onRemoveTextFile ?: {},
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
        if (pendingPrompt != null) {
            com.localchatbot.presentation.components.molecules.UserPromptPanel(
                prompt = pendingPrompt,
                onSelectOption = onSelectPromptOption,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AttachMenuButton(
                onAttachImage = onAttach,
                onAttachTextFile = onAttachTextFile,
                onTemplates = onTemplates,
                // Durante un turno solo se puede encolar texto: adjuntar quedaría a la
                // espera minutos y habría que decidir cómo fusionar varias imágenes en un
                // único mensaje. Se puede ampliar más adelante si hace falta.
                enabled = !sending
            )
            val hasContentNow = value.isNotBlank() || attachedImageBytes != null || attachedTextFiles.isNotEmpty()
            ChatInputField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                onSubmit = if (PlatformCapabilities.isDesktop) {
                    // Con un turno en curso Enter encola (send() lo resuelve), así que ya
                    // no se descarta la pulsación como antes.
                    { if (hasContentNow) dismissAndSend() }
                } else null,
                onPasteImage = onPasteImage
            )
            val hasContent = value.isNotBlank() || attachedImageBytes != null || attachedTextFiles.isNotEmpty()
            when {
                // Con un turno en curso el Stop se queda, y si hay texto aparece además el
                // botón de encolar: el mensaje no se pierde, se manda al terminar.
                sending -> {
                    StopIconButton(onClick = onStop)
                    if (value.isNotBlank()) {
                        IconSquareButton(
                            icon = Icons.Outlined.Schedule,
                            contentDescription = "Encolar mensaje",
                            onClick = dismissAndSend
                        )
                    }
                }
                hasContent -> SendIconButton(enabled = true, onClick = dismissAndSend)
                voiceSupported -> IconSquareButton(icon = Icons.Outlined.Mic, onClick = onVoice)
                else -> SendIconButton(enabled = false, onClick = {})
            }
        }
        if (agentBar != null) {
            Spacer(Modifier.size(Spacing.sm))
            agentBar()
        }
    }
}

/**
 * Botón "+" único que agrupa adjuntar imagen / archivo / plantillas en un menú,
 * en vez de 3 botones sueltos que en mobile dejaban muy poco ancho al input.
 */
@Composable
private fun AttachMenuButton(
    onAttachImage: () -> Unit,
    onAttachTextFile: (() -> Unit)?,
    onTemplates: (() -> Unit)?,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconSquareButton(
            icon = Icons.Default.Add,
            enabled = enabled,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Adjuntar imagen") },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                onClick = { expanded = false; onAttachImage() }
            )
            if (onAttachTextFile != null) {
                DropdownMenuItem(
                    text = { Text("Adjuntar archivo") },
                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    onClick = { expanded = false; onAttachTextFile() }
                )
            }
            if (onTemplates != null) {
                DropdownMenuItem(
                    text = { Text("Plantillas") },
                    leadingIcon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                    onClick = { expanded = false; onTemplates() }
                )
            }
        }
    }
}

@Composable
private fun TextFileChips(
    files: List<AttachedTextFile>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        files.forEach { file ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = Spacing.sm, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRemove(file.name) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Quitar archivo",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillBadge(
    skill: SkillDefinition,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = Spacing.sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "/${skill.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Quitar skill",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun StopIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Stop,
            contentDescription = "Detener",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun AttachmentPreview(
    bytes: ByteArray,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(bytes) { decodeImage(bytes) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Adjunto",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE84A4A))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Quitar imagen",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
