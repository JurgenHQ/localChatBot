package com.localchatbot.core.image

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum

actual fun createImageSaver(): ImageSaver = IosImageSaver()

private class IosImageSaver : ImageSaver {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun saveToGallery(bytes: ByteArray, filename: String): Boolean = runCatching {
        val nsData: NSData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val image = UIImage.imageWithData(nsData) ?: return@runCatching false
        // El selector clásico: guarda en el Photos album del sistema.
        // Requiere NSPhotoLibraryAddUsageDescription en Info.plist.
        UIImageWriteToSavedPhotosAlbum(image, null, null, null)
        true
    }.getOrDefault(false)
}
