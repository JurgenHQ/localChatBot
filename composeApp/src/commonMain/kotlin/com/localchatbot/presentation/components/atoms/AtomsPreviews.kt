package com.localchatbot.presentation.components.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun AppLogoPreview() = PreviewSurface {
    Row(
        modifier = Modifier.padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        AppLogo(size = 32.dp)
        AppLogo(size = 48.dp)
        AppLogo(size = 64.dp)
    }
}

@Preview
@Composable
private fun StatusDotPreview() = PreviewSurface {
    Row(
        modifier = Modifier.padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        StatusDot(color = Color(0xFF2EBD66))
        StatusDot(color = Color(0xFFE84A4A))
        StatusDot(color = Color(0xFFB07E13))
    }
}

@Preview
@Composable
private fun SectionLabelPreview() = PreviewSurface {
    Column(modifier = Modifier.padding(Spacing.lg)) {
        SectionLabel("Conexión")
        SectionLabel("Apariencia")
    }
}

@Preview
@Composable
private fun ButtonsPreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        PrimaryButton(text = "Empezar a chatear", onClick = {})
        PrimaryButton(text = "Deshabilitado", onClick = {}, enabled = false)
        SecondaryButton(text = "Probar conexión de nuevo", onClick = {})
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SendIconButton(enabled = true, onClick = {})
            SendIconButton(enabled = false, onClick = {})
            IconSquareButton(icon = Icons.Default.AutoAwesome, onClick = {})
        }
    }
}

@Preview
@Composable
private fun AppTextFieldPreview() = PreviewSurface {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        AppTextField(value = "", onValueChange = {}, placeholder = "192.168.1.42", monospace = true)
        AppTextField(value = "1234", onValueChange = {}, suffix = "/v1", monospace = true, keyboardType = KeyboardType.Number)
        AppTextField(value = "llama-3.1-8b-instruct", onValueChange = {}, monospace = true)
        val draft = remember { mutableStateOf("Escribe un mensaje…") }
        ChatInputField(value = draft.value, onValueChange = { draft.value = it })
    }
}

@Preview
@Composable
private fun AppTextFieldDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        AppTextField(value = "192.168.1.42", onValueChange = {}, monospace = true)
        ChatInputField(value = "", onValueChange = {})
    }
}
