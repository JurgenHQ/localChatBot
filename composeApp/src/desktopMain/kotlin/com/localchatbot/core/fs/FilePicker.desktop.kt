package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberFilePicker(
    onResult: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit
): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember {
        object : FilePickerLauncher {
            override fun launch() {
                scope.launch(Dispatchers.IO) {
                    val file = chooseFile() ?: return@launch
                    val result = runCatching { parseFile(file) }
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = { text ->
                                if (text != null) onResult(AttachedTextFile(file.name, text))
                                else onError("Formato .doc no soportado — usá .docx o PDF.")
                            },
                            onFailure = { onError("No se pudo leer \"${file.name}\": ${it.message}") }
                        )
                    }
                }
            }
        }
    }
}

/** null = formato reconocido pero no soportado (p. ej. .doc legado). */
private fun parseFile(file: File): String? {
    val bytes = file.readBytes()
    return when (file.extension.lowercase()) {
        "pdf" -> extractPdfText(bytes)
        "docx" -> extractDocxText(bytes)
        "doc" -> null
        else -> decodeText(bytes)
    }
}

private fun decodeText(bytes: ByteArray): String =
    try {
        bytes.toString(Charsets.UTF_8)
    } catch (_: Exception) {
        bytes.toString(Charset.defaultCharset())
    }

private fun extractPdfText(bytes: ByteArray): String =
    PDDocument.load(bytes).use { doc -> PDFTextStripper().getText(doc) }

/** Extrae el texto de word/document.xml dentro del zip de un .docx. */
private fun extractDocxText(bytes: ByteArray): String {
    ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") return parseDocumentXml(zip.readBytes())
            entry = zip.nextEntry
        }
    }
    error("El .docx no contiene word/document.xml")
}

/** <w:p> = párrafo, <w:t> = texto. DOM real en vez de regex por los namespaces/attrs. */
private fun parseDocumentXml(xml: ByteArray): String {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }
    val doc = factory.newDocumentBuilder()
        .parse(InputSource(StringReader(xml.toString(Charsets.UTF_8))))
    val paragraphs = doc.getElementsByTagNameNS("*", "p")
    return buildString {
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as Element
            val texts = p.getElementsByTagNameNS("*", "t")
            for (j in 0 until texts.length) append(texts.item(j).textContent)
            append('\n')
        }
    }.trim()
}

private suspend fun chooseFile(): File? {
    val deferred = CompletableDeferred<File?>()
    // Los diálogos AWT/Swing deben crearse en el EDT.
    SwingUtilities.invokeLater {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val result = if (isMac || isWindows) {
            // FileDialog es un peer nativo en ambos SO: en Windows delega al diálogo
            // común de Explorer real (el mismo "Abrir" de cualquier app Win32), a
            // diferencia de JFileChooser que siempre se dibuja con el look Java/Metal.
            // Sin filtro de extensiones (AWT FileDialog no soporta una lista desplegable
            // de filtros con descripción, solo un patrón crudo poco fiable entre JDKs).
            val dialog = FileDialog(null as Frame?, "Seleccionar archivo", FileDialog.LOAD)
            dialog.isMultipleMode = false
            dialog.isVisible = true
            if (dialog.file != null) File(dialog.directory, dialog.file) else null
        } else {
            // Linux: sin peer nativo unificado, se mantiene JFileChooser con filtro.
            val chooser = JFileChooser().apply {
                dialogTitle = "Seleccionar archivo"
                isMultiSelectionEnabled = false
                addChoosableFileFilter(
                    FileNameExtensionFilter(
                        "Documentos y texto (*.pdf, *.docx, *.txt, *.md, ...)",
                        "pdf", "docx",
                        "txt", "md", "kt", "kts", "py", "js", "ts", "tsx", "jsx",
                        "json", "yaml", "yml", "toml", "xml", "csv", "sh", "sql",
                        "java", "swift", "rs", "go", "c", "cpp", "h", "cs", "rb",
                        "html", "css", "scss", "sass", "log", "conf", "ini", "env",
                        "gradle", "properties"
                    )
                )
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
        deferred.complete(result)
    }
    return deferred.await()
}
