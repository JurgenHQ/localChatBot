package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.decompressedDataUsingAlgorithm
import platform.PDFKit.PDFDocument
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * `documentTypes = ["public.item"]` (UTI raíz = cualquier archivo): dejamos que
 * la extracción decida por extensión, igual que en Desktop/Android. Modo
 * `.Import` copia el archivo al sandbox de la app — evita lidiar con
 * security-scoped URLs del archivo original.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(
    onResult: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit
): FilePickerLauncher {
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>
            ) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                handlePickedUrl(url, onResult, onError)
            }
        }
    }
    return remember(delegate) {
        object : FilePickerLauncher {
            override fun launch() {
                val picker = UIDocumentPickerViewController(
                    documentTypes = listOf("public.item"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
                )
                picker.delegate = delegate
                UIApplication.sharedApplication.keyWindow?.rootViewController
                    ?.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun handlePickedUrl(url: NSURL, onResult: (AttachedTextFile) -> Unit, onError: (String) -> Unit) {
    val name = url.lastPathComponent ?: "archivo"
    val data = NSData.dataWithContentsOfURL(url)
    if (data == null) {
        onError("No se pudo leer \"$name\"")
        return
    }
    when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> {
            val text = extractPdfText(data)
            if (text != null) onResult(AttachedTextFile(name, text))
            else onError("No se pudo extraer texto de \"$name\"")
        }
        "docx" -> {
            val text = runCatching { extractDocxText(data.toByteArray()) }.getOrNull()
            if (!text.isNullOrBlank()) onResult(AttachedTextFile(name, text))
            else onError("No se pudo extraer texto de \"$name\"")
        }
        "doc" -> onError("Formato .doc no soportado — usá .docx o PDF.")
        else -> {
            val text = NSString.create(data, NSUTF8StringEncoding) as String?
            if (text != null) onResult(AttachedTextFile(name, text))
            else onError("No se pudo leer \"$name\" como texto")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun extractPdfText(data: NSData): String? {
    val doc = PDFDocument(data = data) ?: return null
    return buildString {
        for (i in 0 until doc.pageCount().toInt()) {
            doc.pageAtIndex(i.toULong())?.string()?.let { append(it); append('\n') }
        }
    }.trim()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

/**
 * Extrae el texto de word/document.xml dentro del zip de un .docx, como en
 * Android/Desktop. iOS no tiene API pública de unzip, así que se parsea la
 * estructura ZIP a mano (directorio central) y se infla la entrada con
 * Foundation (`decompressedDataUsingAlgorithm`, deflate crudo RFC 1951).
 */
private fun extractDocxText(bytes: ByteArray): String {
    val xml = readZipEntry(bytes, "word/document.xml")
        ?: error("El .docx no contiene word/document.xml")
    return parseDocumentXml(xml.decodeToString())
}

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(offset: Int): Long =
    (u16(offset).toLong()) or (u16(offset + 2).toLong() shl 16)

/** Busca [entryName] vía el directorio central del ZIP y devuelve sus bytes inflados. */
@OptIn(ExperimentalForeignApi::class)
private fun readZipEntry(zip: ByteArray, entryName: String): ByteArray? {
    // End Of Central Directory (sig 0x06054b50): en los últimos 65557 bytes
    // (22 fijos + comentario de hasta 64k). Se busca hacia atrás.
    var eocd = -1
    val minEocd = maxOf(0, zip.size - 22 - 0xFFFF)
    for (i in zip.size - 22 downTo minEocd) {
        if (zip.u32(i) == 0x06054b50L) { eocd = i; break }
    }
    if (eocd < 0) return null
    val entryCount = zip.u16(eocd + 10)
    var pos = zip.u32(eocd + 16).toInt() // offset del directorio central

    repeat(entryCount) {
        if (pos + 46 > zip.size || zip.u32(pos) != 0x02014b50L) return null
        val method = zip.u16(pos + 10)
        val compSize = zip.u32(pos + 20).toInt()
        val nameLen = zip.u16(pos + 28)
        val extraLen = zip.u16(pos + 30)
        val commentLen = zip.u16(pos + 32)
        val localOffset = zip.u32(pos + 42).toInt()
        val name = zip.decodeToString(pos + 46, pos + 46 + nameLen)
        if (name == entryName) {
            // El local header repite name/extra con longitudes propias (pueden diferir).
            if (localOffset + 30 > zip.size || zip.u32(localOffset) != 0x04034b50L) return null
            val dataStart = localOffset + 30 + zip.u16(localOffset + 26) + zip.u16(localOffset + 28)
            if (dataStart + compSize > zip.size) return null
            val compressed = zip.copyOfRange(dataStart, dataStart + compSize)
            return when (method) {
                0 -> compressed // stored, sin comprimir
                8 -> inflateRaw(compressed)
                else -> null
            }
        }
        pos += 46 + nameLen + extraLen + commentLen
    }
    return null
}

/** Infla un stream deflate crudo (método 8 de ZIP) con Foundation. */
@OptIn(ExperimentalForeignApi::class)
private fun inflateRaw(compressed: ByteArray): ByteArray? {
    val nsData = compressed.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = compressed.size.toULong())
    }
    val inflated = nsData.decompressedDataUsingAlgorithm(
        NSDataCompressionAlgorithmZlib, error = null
    ) ?: return null
    return inflated.toByteArray()
}

/**
 * <w:p> = párrafo, <w:t> = texto (mismo criterio que Android/Desktop, que usan
 * DOM). Sin parser XML en Native se usan regex tolerantes al prefijo de
 * namespace; los atributos de <w:t> (p. ej. xml:space) se aceptan.
 */
private val DOCX_PARAGRAPH_SPLIT = Regex("</(?:[A-Za-z0-9]+:)?p>")
private val DOCX_TEXT_RUN = Regex("<(?:[A-Za-z0-9]+:)?t(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9]+:)?t>", RegexOption.DOT_MATCHES_ALL)

private fun parseDocumentXml(xml: String): String = buildString {
    xml.split(DOCX_PARAGRAPH_SPLIT).forEach { paragraph ->
        val runs = DOCX_TEXT_RUN.findAll(paragraph).toList()
        if (runs.isNotEmpty()) {
            runs.forEach { append(decodeXmlEntities(it.groupValues[1])) }
            append('\n')
        }
    }
}.trim()

private fun decodeXmlEntities(text: String): String = text
    .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
    .replace(Regex("&#([0-9]+);")) { it.groupValues[1].toInt().toChar().toString() }
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
