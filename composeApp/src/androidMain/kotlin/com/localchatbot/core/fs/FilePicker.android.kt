package com.localchatbot.core.fs

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.localchatbot.AppContextHolder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

private var pdfBoxInitialized = false

@Composable
actual fun rememberFilePicker(
    onResult: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit
): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    val resolver = AppContextHolder.context.contentResolver
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "archivo"
            val result = runCatching { parseUri(resolver, uri, name) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { text ->
                        if (text != null) onResult(AttachedTextFile(name, text))
                        else onError("Formato .doc no soportado — usá .docx o PDF.")
                    },
                    onFailure = { onError("No se pudo leer \"$name\": ${it.message}") }
                )
            }
        }
    }
    return remember(launcher) {
        object : FilePickerLauncher {
            override fun launch() = launcher.launch(arrayOf("*/*"))
        }
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

/** null = formato reconocido pero no soportado (p. ej. .doc legado). */
private fun parseUri(resolver: ContentResolver, uri: Uri, name: String): String? {
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo abrir el archivo")
    return when (name.substringAfterLast('.', "").lowercase()) {
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

private fun extractPdfText(bytes: ByteArray): String {
    if (!pdfBoxInitialized) {
        PDFBoxResourceLoader.init(AppContextHolder.context)
        pdfBoxInitialized = true
    }
    return PDDocument.load(bytes).use { doc -> PDFTextStripper().getText(doc) }
}

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
