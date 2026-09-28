package com.localchatbot.core.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** En móvil las actualizaciones las gestiona la tienda / la instalación manual. */
actual fun createAppUpdater(scope: CoroutineScope, client: HttpClient): AppUpdater = object : AppUpdater {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Unsupported)
    override val currentVersion: String? = null
    override fun check() = Unit
    override fun install() = Unit
}
