package com.localchatbot.core.fs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.io.File

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDropTarget(
    onImage: (ByteArray) -> Unit,
    onTextFile: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit,
    onHover: (Boolean) -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    // El target se recuerda una vez; los callbacks pueden cambiar entre recomposiciones.
    val image by rememberUpdatedState(onImage)
    val text by rememberUpdatedState(onTextFile)
    val error by rememberUpdatedState(onError)
    val hover by rememberUpdatedState(onHover)

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val files = droppedFiles(event)
                if (files.isEmpty()) return false
                scope.launch {
                    val (images, others) = files.partition { it.extension.lowercase() in IMAGE_EXTENSIONS }
                    images.firstOrNull()?.let { file ->
                        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                        if (bytes != null) image(bytes) else error("No se pudo leer \"${file.name}\"")
                    }
                    if (images.size > 1) error("Solo se adjunta una imagen por mensaje: se usó \"${images.first().name}\".")
                    others.forEach { file ->
                        val result = withContext(Dispatchers.IO) { runCatching { parseAttachmentFile(file) } }
                        result.fold(
                            onSuccess = { content ->
                                if (content != null) text(AttachedTextFile(file.name, content))
                                else error("Formato .doc no soportado — usá .docx o PDF.")
                            },
                            onFailure = { error("No se pudo leer \"${file.name}\": ${it.message}") }
                        )
                    }
                }
                return true
            }
        }
    }
    // Mientras se arrastra solo se mira el tipo: leer la lista de archivos antes del drop
    // lanza InvalidDnDOperationException en algunos JDK.
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            runCatching { event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)
        },
        target = target
    )
}

/** Archivos (no carpetas) del evento; vacío si lo arrastrado no es una lista de archivos. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return@runCatching emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().filter { it.isFile }
}.getOrDefault(emptyList())
