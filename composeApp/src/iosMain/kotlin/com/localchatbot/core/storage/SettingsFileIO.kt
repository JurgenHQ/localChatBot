package com.localchatbot.core.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

private const val DEFAULT_FILE_NAME = "localchatbot-settings.json"

private fun jsonUTType(): UTType =
    UTType.typeWithIdentifier("public.json") ?: UTType.typeWithIdentifier("public.text")!!

private fun topViewController(): platform.UIKit.UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberSettingsExporter(
    onError: (String) -> Unit
): (String) -> Unit {
    // Retiene el delegate vivo mientras el picker está presentado.
    val delegateHolder = remember { arrayOfNulls<UIDocumentPickerDelegateProtocol>(1) }
    return remember(onError) {
        { json ->
            val tmp = NSURL.fileURLWithPath(NSTemporaryDirectory() + DEFAULT_FILE_NAME)
            val ok = (json as NSString).writeToURL(tmp, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (!ok) {
                onError("No se pudo preparar el archivo de exportación.")
            } else {
                val picker = UIDocumentPickerViewController(forExportingURLs = listOf(tmp))
                val delegate = PickerDelegate(onResult = {}, onDone = { delegateHolder[0] = null })
                delegateHolder[0] = delegate
                picker.delegate = delegate
                topViewController()?.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberSettingsImporter(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val delegateHolder = remember { arrayOfNulls<UIDocumentPickerDelegateProtocol>(1) }
    return remember(onResult, onError) {
        {
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(jsonUTType()))
            val delegate = PickerDelegate(
                onResult = { url ->
                    val accessed = url.startAccessingSecurityScopedResource()
                    val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
                    if (accessed) url.stopAccessingSecurityScopedResource()
                    if (text != null) onResult(text as String) else onError("No se pudo leer el archivo.")
                },
                onDone = { delegateHolder[0] = null }
            )
            delegateHolder[0] = delegate
            picker.delegate = delegate
            topViewController()?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

private class PickerDelegate(
    private val onResult: (NSURL) -> Unit,
    private val onDone: () -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(onResult)
        onDone()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDone()
    }
}
