package com.localchatbot.core.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.localchatbot.AppContextHolder
import kotlinx.coroutines.CompletableDeferred

/**
 * Hub que la `MainActivity` rellena al crearse, exponiendo un launcher para pedir RECORD_AUDIO.
 * Se mantiene a nivel de proceso para que `commonMain` no dependa de la Activity.
 */
object VoicePermissionBridge {
    var launcher: ActivityResultLauncher<String>? = null
    var pending: CompletableDeferred<Boolean>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            pending?.complete(granted)
            pending = null
        }
    }
}

actual suspend fun requestVoicePermissions(): Boolean {
    val context = AppContextHolder.context
    val already = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (already) return true

    val launcher = VoicePermissionBridge.launcher ?: return false
    val deferred = CompletableDeferred<Boolean>()
    VoicePermissionBridge.pending = deferred
    launcher.launch(Manifest.permission.RECORD_AUDIO)
    return deferred.await()
}
