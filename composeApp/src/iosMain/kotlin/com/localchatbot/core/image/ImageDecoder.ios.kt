package com.localchatbot.core.image

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toComposeImageBitmap

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
