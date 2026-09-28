package com.localchatbot.core.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Actualización de la app de escritorio desde el pre-release rodante `latest` de GitHub,
 * que el CI publica en cada push a `main` (ver `windows-build.yml`). Hoy solo Windows: es la
 * única plataforma con instalador publicado. En el resto [state] queda en
 * [UpdateState.Unsupported] y la UI no muestra nada.
 */
interface AppUpdater {
    val state: StateFlow<UpdateState>

    /** Versión instalada, o null si no corre desde un instalador (p. ej. `./gradlew run`). */
    val currentVersion: String?

    fun check()

    /** Descarga el instalador de [UpdateState.Available] y lo lanza; la app se cierra. */
    fun install()
}

sealed interface UpdateState {
    data object Unsupported : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val downloadUrl: String, val fileName: String) : UpdateState
    data class Downloading(val version: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

expect fun createAppUpdater(scope: CoroutineScope, client: HttpClient): AppUpdater

/** Repositorio y tag del pre-release que publica el CI. */
const val UPDATE_REPO = "JurgenHQ/localChatBot"
const val UPDATE_RELEASE_TAG = "latest"

/**
 * Compara versiones `x.y.z` numéricamente, campo a campo (`1.0.10` > `1.0.9`, que como texto
 * sale al revés). Lo que no es número cuenta como 0.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

private val MSI_VERSION = Regex("""-(\d+(?:\.\d+)+)\.msi$""", RegexOption.IGNORE_CASE)

/**
 * Del JSON de `GET /repos/{repo}/releases/tags/latest`, el `.msi` de versión más alta si es
 * más nuevo que [current]; null si no hay nada más nuevo. Se elige el mayor y no el primero:
 * si el CI no llegó a borrar un asset viejo, el release puede traer más de uno.
 */
fun pickUpdate(release: JsonObject, current: String): UpdateState.Available? {
    val assets = release["assets"]?.jsonArray ?: return null
    return assets.mapNotNull { el ->
        val asset = el.jsonObject
        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val version = MSI_VERSION.find(name)?.groupValues?.get(1) ?: return@mapNotNull null
        UpdateState.Available(version, url, name)
    }
        .maxWithOrNull { x, y -> compareVersions(x.version, y.version) }
        ?.takeIf { compareVersions(it.version, current) > 0 }
}
