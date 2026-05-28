package com.localchatbot.core.image

/**
 * Guarda bytes de imagen en la galería del dispositivo. Cada plataforma decide la
 * carpeta concreta (Pictures en Android, Photo Library en iOS).
 */
interface ImageSaver {
    /** Devuelve true si la imagen se guardó correctamente. */
    suspend fun saveToGallery(bytes: ByteArray, filename: String): Boolean
}

expect fun createImageSaver(): ImageSaver
