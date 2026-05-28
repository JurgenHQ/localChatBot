package com.localchatbot.core.image

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.localchatbot.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createImageSaver(): ImageSaver = AndroidImageSaver()

private class AndroidImageSaver : ImageSaver {
    override suspend fun saveToGallery(bytes: ByteArray, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = AppContextHolder.context
                val resolver = context.contentResolver

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/LocalChatBot"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return@runCatching false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            }.getOrDefault(false)
        }
}
