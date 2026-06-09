package com.localchatbot.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.preat.peekaboo.image.picker.SelectionMode
import com.preat.peekaboo.image.picker.rememberImagePickerLauncher

@Composable
actual fun rememberImagePicker(onResult: (ByteArray) -> Unit): ImagePickerLauncher {
    val scope = rememberCoroutineScope()
    val launcher = rememberImagePickerLauncher(
        selectionMode = SelectionMode.Single,
        scope = scope,
        onResult = { byteArrays -> byteArrays.firstOrNull()?.let(onResult) }
    )
    return remember(launcher) {
        object : ImagePickerLauncher {
            override fun launch() = launcher.launch()
        }
    }
}
