package com.localchatbot.presentation.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SecondaryButton
import com.localchatbot.presentation.components.molecules.ModelPickerList
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun SettingsEditorSheet(
    viewModel: SettingsEditorViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEditorSheetContent(
        state = state,
        onTextChange = viewModel::onTextChange,
        onThemeChange = { viewModel.onThemeChange(it); viewModel.save(onDismiss) },
        onAccentChange = { viewModel.onAccentChange(it); viewModel.save(onDismiss) },
        onModelSelected = viewModel::onModelSelected,
        onSave = { viewModel.save(onDismiss) },
        onDismiss = onDismiss
    )
}

@Composable
fun SettingsEditorSheetContent(
    state: SettingsEditorUiState,
    onTextChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAccentChange: (Long) -> Unit,
    onModelSelected: (String) -> Unit = {},
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Scrim con pointerInput en lugar de clickable: clickable añade semántica
    // de teclado en desktop (Espacio/Enter = click con foco) y cerraba el sheet
    // al escribir un espacio en los campos de texto.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(MaterialTheme.colorScheme.background)
                // Consume los taps para que no lleguen al scrim y cierren el sheet.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            when (state.editor) {
                SettingsEditor.Ip -> TextEditorBody(
                    title = "Host / IP",
                    value = state.textDraft,
                    placeholder = "192.168.1.42  o  api.openai.com",
                    keyboardType = KeyboardType.Uri,
                    onChange = onTextChange,
                    canSave = state.canSaveText,
                    onSave = onSave
                )
                SettingsEditor.Port -> TextEditorBody(
                    title = "Puerto (opcional)",
                    value = state.textDraft,
                    placeholder = "1234",
                    keyboardType = KeyboardType.Number,
                    onChange = onTextChange,
                    canSave = state.canSaveText,
                    onSave = onSave
                )
                SettingsEditor.Model -> {
                    TextEditorBody(
                        title = "Modelo",
                        value = state.textDraft,
                        placeholder = "llama-3.1-8b-instruct",
                        keyboardType = KeyboardType.Text,
                        onChange = onTextChange,
                        canSave = state.canSaveText,
                        onSave = onSave
                    )
                    if (state.availableModels.isNotEmpty() || state.loadingModels) {
                        ModelPickerList(
                            models = state.availableModels,
                            selected = state.textDraft,
                            onSelect = onModelSelected,
                            loading = state.loadingModels
                        )
                    }
                }
                SettingsEditor.ApiKey -> TextEditorBody(
                    title = "API key del modelo",
                    value = state.textDraft,
                    placeholder = "sk-... (opcional)",
                    keyboardType = KeyboardType.Password,
                    onChange = onTextChange,
                    canSave = state.canSaveText,
                    onSave = onSave
                )
                SettingsEditor.Theme -> ThemeEditorBody(
                    current = state.themeDraft,
                    onSelect = onThemeChange
                )
                SettingsEditor.Accent -> AccentEditorBody(
                    onSelect = onAccentChange
                )
                SettingsEditor.TavilyApiKey -> TextEditorBody(
                    title = "Tavily API key",
                    value = state.textDraft,
                    placeholder = "tvly-...",
                    keyboardType = KeyboardType.Password,
                    onChange = onTextChange,
                    canSave = state.canSaveText,
                    onSave = onSave
                )
                SettingsEditor.SystemPrompt -> TextEditorBody(
                    title = "System prompt",
                    value = state.textDraft,
                    placeholder = "Responde siempre en español…",
                    keyboardType = KeyboardType.Text,
                    onChange = onTextChange,
                    canSave = state.canSaveText,
                    onSave = onSave
                )
                SettingsEditor.ImageServiceUrl -> TextEditorBody(
                    title = "URL del servicio multimedia",
                    value = state.textDraft,
                    placeholder = "http://192.168.1.42:8080",
                    keyboardType = KeyboardType.Uri,
                    onChange = onTextChange,
                    canSave = state.canSaveText,
                    onSave = onSave
                )
            }
            SecondaryButton(text = "Cancelar", onClick = onDismiss)
        }
    }
}

@Composable
private fun TextEditorBody(
    title: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    AppTextField(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        keyboardType = keyboardType,
        monospace = true
    )
    PrimaryButton(text = "Guardar", onClick = onSave, enabled = canSave)
}

@Composable
private fun ThemeEditorBody(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Text("Tema", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ThemeMode.entries.forEach { mode ->
            val label = when (mode) {
                ThemeMode.System -> "Automático"
                ThemeMode.Light -> "Claro"
                ThemeMode.Dark -> "Oscuro"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        if (current == mode) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onSelect(mode) }
                    .padding(Spacing.lg)
            ) {
                Text(label, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun AccentEditorBody(onSelect: (Long) -> Unit) {
    val colors = listOf(
        0xFF2C5AFFL, 0xFF7C4DFFL, 0xFF2EBD66L,
        0xFFE84A4AL, 0xFFFF8A00L, 0xFF0F1115L
    )
    Text("Color de acento", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(c))
                    .clickable { onSelect(c) }
            )
        }
    }
}

@Preview
@Composable
private fun EditorIpPreview() = PreviewSurface {
    SettingsEditorSheetContent(
        state = SettingsEditorUiState(editor = SettingsEditor.Ip, textDraft = "192.168.1.42"),
        onTextChange = {}, onThemeChange = {}, onAccentChange = {}, onSave = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun EditorPortPreview() = PreviewSurface {
    SettingsEditorSheetContent(
        state = SettingsEditorUiState(editor = SettingsEditor.Port, textDraft = "1234"),
        onTextChange = {}, onThemeChange = {}, onAccentChange = {}, onSave = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun EditorModelPreview() = PreviewSurface {
    SettingsEditorSheetContent(
        state = SettingsEditorUiState(editor = SettingsEditor.Model, textDraft = "llama-3.1-8b-instruct"),
        onTextChange = {}, onThemeChange = {}, onAccentChange = {}, onSave = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun EditorThemePreview() = PreviewSurface {
    SettingsEditorSheetContent(
        state = SettingsEditorUiState(editor = SettingsEditor.Theme, themeDraft = ThemeMode.System),
        onTextChange = {}, onThemeChange = {}, onAccentChange = {}, onSave = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun EditorAccentPreview() = PreviewSurface {
    SettingsEditorSheetContent(
        state = SettingsEditorUiState(editor = SettingsEditor.Accent),
        onTextChange = {}, onThemeChange = {}, onAccentChange = {}, onSave = {}, onDismiss = {}
    )
}

@Preview
@Composable
private fun EditorIpDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    SettingsEditorSheetContent(
        state = SettingsEditorUiState(editor = SettingsEditor.Ip, textDraft = "192.168.1.42"),
        onTextChange = {}, onThemeChange = {}, onAccentChange = {}, onSave = {}, onDismiss = {}
    )
}
