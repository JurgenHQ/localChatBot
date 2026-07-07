package com.localchatbot.presentation.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.localchatbot.core.state.PendingUserPrompt
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import androidx.compose.ui.graphics.Color

/**
 * Panel que muestra la pregunta lanzada por el modelo vía `ask_user` y sus
 * opciones como chips tocables. Se renderiza sobre el composer; el input de
 * texto sigue activo (el usuario puede escribir una respuesta libre).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserPromptPanel(
    prompt: PendingUserPrompt,
    onSelectOption: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val panelBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
    val panelBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    var freeText by remember { mutableStateOf("") }

    val submitFreeText = {
        val trimmed = freeText.trim()
        if (trimmed.isNotEmpty()) {
            freeText = ""
            onSelectOption(trimmed)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(panelBg)
            .border(1.dp, panelBorder, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.padding(top = 1.dp)
            )
            Text(
                prompt.question,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
        if (prompt.options.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                prompt.options.forEach { option ->
                    OptionChip(text = option, onClick = { onSelectOption(option) })
                }
            }
        }
        if (prompt.allowFreeText) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(Radius.sm))
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                BasicTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitFreeText() }),
                    decorationBox = { inner ->
                        if (freeText.isEmpty()) {
                            Text(
                                "Escribe tu respuesta…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor.copy(alpha = 0.5f)
                            )
                        }
                        inner()
                    }
                )
                if (freeText.isNotEmpty()) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Enviar",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { submitFreeText() }
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(Radius.pill)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
