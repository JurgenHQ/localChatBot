package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Convierte el elemento en destino de archivos arrastrados desde el explorador del SO
 * (solo desktop; en móvil devuelve el mismo [Modifier]).
 *
 * Las imágenes llegan como bytes a [onImage] (solo la primera: el mensaje admite una) y el
 * resto pasa por la misma extracción que el botón de adjuntar (PDF, DOCX, texto) hacia
 * [onTextFile]. [onHover] indica si hay algo encima, para resaltar la zona.
 */
@Composable
expect fun Modifier.fileDropTarget(
    onImage: (ByteArray) -> Unit,
    onTextFile: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit,
    onHover: (Boolean) -> Unit = {}
): Modifier
