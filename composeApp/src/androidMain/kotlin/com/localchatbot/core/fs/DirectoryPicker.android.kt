package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Stub: la sección de Settings que invoca al picker está gated por
 * `PlatformCapabilities.isDesktop`, por lo que en Android nunca se llama.
 */
@Composable
actual fun rememberDirectoryPicker(onResult: (String) -> Unit): DirectoryPickerLauncher =
    remember { NoopDirectoryPicker }

private object NoopDirectoryPicker : DirectoryPickerLauncher {
    override fun launch() = Unit
}
