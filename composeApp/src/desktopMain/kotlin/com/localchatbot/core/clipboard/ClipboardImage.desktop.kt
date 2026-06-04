package com.localchatbot.core.clipboard

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun readClipboardImageBytes(): ByteArray? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
    val img = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
    val w = img.getWidth(null).takeIf { it > 0 } ?: return null
    val h = img.getHeight(null).takeIf { it > 0 } ?: return null
    val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    buffered.createGraphics().apply {
        drawImage(img, 0, 0, null)
        dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(buffered, "PNG", out)
    out.toByteArray()
}.getOrNull()
