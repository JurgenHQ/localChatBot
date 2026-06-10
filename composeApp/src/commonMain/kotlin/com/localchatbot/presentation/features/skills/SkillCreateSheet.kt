package com.localchatbot.presentation.features.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.ScriptParam
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.model.SkillScript
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SecondaryButton

@Composable
fun SkillCreateSheet(
    editingSkill: SkillDefinition?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, fullDescription: String, systemPromptAddition: String, scripts: List<SkillScript>) -> Unit
) {
    var name by remember(editingSkill) { mutableStateOf(editingSkill?.name ?: "") }
    var description by remember(editingSkill) { mutableStateOf(editingSkill?.description ?: "") }
    var fullDescription by remember(editingSkill) { mutableStateOf(editingSkill?.fullDescription ?: "") }
    var systemPrompt by remember(editingSkill) { mutableStateOf(editingSkill?.systemPromptAddition ?: "") }
    val scripts = remember(editingSkill) { mutableStateListOf<SkillScript>().also { it.addAll(editingSkill?.scripts ?: emptyList()) } }

    var showAddScript by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                if (editingSkill == null) "Crear skill" else "Editar skill",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            FieldGroup("Nombre") {
                AppTextField(value = name, onValueChange = { name = it }, placeholder = "Ej: Revisor de código")
            }
            FieldGroup("Descripción corta") {
                AppTextField(value = description, onValueChange = { description = it }, placeholder = "Una línea visible en el índice del agente")
            }
            FieldGroup("Descripción completa (opcional)") {
                AppTextField(value = fullDescription, onValueChange = { fullDescription = it }, placeholder = "Detalle para la ficha del skill")
            }
            FieldGroup("Instrucciones del sistema") {
                AppTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = "Instrucciones enviadas al modelo cuando el skill se activa…",
                    singleLine = false
                )
            }

            // Scripts section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Scripts (desktop, opcional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { showAddScript = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = "Añadir script", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (scripts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    scripts.forEachIndexed { index, script ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(script.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(script.command.take(50), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scripts.removeAt(index) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (index < scripts.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                        }
                    }
                }
            }

            if (showAddScript) {
                AddScriptForm(
                    onAdd = { script ->
                        scripts.add(script)
                        showAddScript = false
                    },
                    onCancel = { showAddScript = false }
                )
            }

            val canSave = name.isNotBlank() && description.isNotBlank() && systemPrompt.isNotBlank()
            PrimaryButton(
                text = if (editingSkill == null) "Crear" else "Guardar",
                enabled = canSave,
                onClick = { onSave(name, description, fullDescription, systemPrompt, scripts.toList()) },
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton(text = "Cancelar", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AddScriptForm(onAdd: (SkillScript) -> Unit, onCancel: () -> Unit) {
    var sName by remember { mutableStateOf("") }
    var sDesc by remember { mutableStateOf("") }
    var sCmd by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text("Nuevo script", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        AppTextField(value = sName, onValueChange = { sName = it }, placeholder = "Nombre (ej: limpiar_cache)")
        AppTextField(value = sDesc, onValueChange = { sDesc = it }, placeholder = "Descripción para el modelo")
        AppTextField(
            value = sCmd,
            onValueChange = { sCmd = it },
            placeholder = "Comando (usa {{param}} para parámetros)",
            singleLine = false,
            monospace = true
        )
        Text(
            "Usa {{nombre}} en el comando para que el modelo rellene parámetros.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(text = "Cancelar", onClick = onCancel, modifier = Modifier.weight(1f))
            PrimaryButton(
                text = "Añadir",
                enabled = sName.isNotBlank() && sCmd.isNotBlank(),
                onClick = {
                    val params = Regex("""\{\{(\w+)\}\}""").findAll(sCmd)
                        .map { ScriptParam(name = it.groupValues[1], description = it.groupValues[1]) }
                        .distinctBy { it.name }
                        .toList()
                    onAdd(SkillScript(name = sName.trim(), description = sDesc.trim(), command = sCmd.trim(), params = params))
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FieldGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
