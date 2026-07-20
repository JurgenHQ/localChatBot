package com.localchatbot.presentation.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.localchatbot.core.clipboard.readClipboardImageBytes
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.localchatbot.core.theme.Radius

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false,
    suffix: String? = null,
    singleLine: Boolean = true
) {
    val textStyle = LocalTextStyle.current.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .defaultMinSize(minHeight = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f, fill = suffix == null),
            singleLine = singleLine,
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    inner()
                }
            }
        )
        if (suffix != null) {
            Text(
                suffix,
                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

@Composable
fun ChatInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Escribe un mensaje…",
    onSubmit: (() -> Unit)? = null,
    onPasteImage: ((ByteArray) -> Unit)? = null
) {
    // Estado interno con selección para poder insertar '\n' en la posición real
    // del cursor con Shift+Enter; el API externo sigue siendo String.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    if (fieldValue.text != value) {
        // Cambio externo del texto (enviar limpia el campo, seleccionar una
        // skill deja el argumento): re-sincronizar con el cursor al final.
        fieldValue = TextFieldValue(value, selection = TextRange(value.length))
    }
    val insertNewlineAtCursor = {
        val sel = fieldValue.selection
        val newText = fieldValue.text.replaceRange(sel.min, sel.max, "\n")
        fieldValue = TextFieldValue(newText, selection = TextRange(sel.min + 1))
        onValueChange(newText)
    }
    val submitOnEnter = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            if (event.isShiftPressed) {
                insertNewlineAtCursor()
                return@onPreviewKeyEvent true
            }
            if (onSubmit != null) {
                onSubmit()
                return@onPreviewKeyEvent true
            }
        }
        if (event.type == KeyEventType.KeyDown && event.key == Key.V &&
            (event.isCtrlPressed || event.isMetaPressed) && onPasteImage != null
        ) {
            val bytes = readClipboardImageBytes()
            if (bytes != null) {
                onPasteImage(bytes)
                return@onPreviewKeyEvent true
            }
        }
        false
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .defaultMinSize(minHeight = 44.dp)
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != value) onValueChange(it.text)
            },
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).then(submitOnEnter),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
    }
}
