package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Sin arrastrar y soltar desde el SO en móvil: se adjunta con los botones del composer. */
@Composable
actual fun Modifier.fileDropTarget(
    onImage: (ByteArray) -> Unit,
    onTextFile: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit,
    onHover: (Boolean) -> Unit
): Modifier = this
