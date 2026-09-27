package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.presentation.features.chat.SlashCommand

/**
 * Popup que aparece al escribir `/` en el composer: lista **todos** los comandos
 * disponibles y las skills instaladas, filtrados por lo que se vaya escribiendo.
 *
 * Los comandos van primero porque actúan sobre la app (compactar, exportar) y son pocos y
 * fijos; las skills van después, son muchas y cambian según lo instalado. Cada sección
 * lleva encabezado: las dos se invocan igual pero hacen cosas distintas — un comando ejecuta
 * una acción y no manda nada al modelo, una skill modifica el próximo mensaje.
 */
@Composable
fun SlashSuggestionPopup(
    query: String,
    commands: List<SlashCommand>,
    skills: List<SkillDefinition>,
    onSelectCommand: (SlashCommand) -> Unit,
    onSelectSkill: (SkillDefinition) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredCommands = if (query.isBlank()) commands else commands.filter {
        it.id.contains(query, ignoreCase = true) ||
            it.aliases.any { alias -> alias.contains(query, ignoreCase = true) } ||
            it.description.contains(query, ignoreCase = true)
    }
    val filteredSkills = if (query.isBlank()) skills else skills.filter {
        it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
    }
    if (filteredCommands.isEmpty() && filteredSkills.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .shadow(4.dp, RoundedCornerShape(Radius.md))
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        if (filteredCommands.isNotEmpty()) {
            SectionHeader("Comandos")
            filteredCommands.forEachIndexed { index, cmd ->
                SuggestionRow(
                    token = cmd.token,
                    description = cmd.description,
                    onClick = { onSelectCommand(cmd) }
                )
                if (index < filteredCommands.lastIndex) RowDivider()
            }
        }
        if (filteredSkills.isNotEmpty()) {
            SectionHeader("Skills")
            filteredSkills.forEachIndexed { index, skill ->
                SuggestionRow(
                    token = "/${skill.id}",
                    description = skill.description,
                    onClick = { onSelectSkill(skill) }
                )
                if (index < filteredSkills.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs)
    )
}

@Composable
private fun SuggestionRow(token: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                token,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp
    )
}
