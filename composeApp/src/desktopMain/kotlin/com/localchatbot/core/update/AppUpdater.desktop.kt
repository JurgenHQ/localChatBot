package com.localchatbot.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.system.exitProcess

actual fun createAppUpdater(scope: CoroutineScope, client: HttpClient): AppUpdater {
    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    // jpackage define `jpackage.app-version` en el launcher del instalador. Sin él la app
    // corre desde Gradle o un IDE, y "actualizar" instalaría encima de nada.
    val version = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
    return if (isWindows && version != null) WindowsMsiUpdater(scope, client, version) else UnsupportedUpdater
}

private object UnsupportedUpdater : AppUpdater {
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Unsupported)
    override val currentVersion: String? = null
    override fun check() = Unit
    override fun install() = Unit
}

/**
 * Busca un `.msi` más nuevo en el release `latest` y lo instala con `msiexec`. El instalador
 * tiene un `upgradeUuid` fijo y la versión crece con cada build del CI, así que el MSI nuevo
 * reemplaza al instalado en vez de instalarse al lado.
 */
private class WindowsMsiUpdater(
    private val scope: CoroutineScope,
    private val client: HttpClient,
    override val currentVersion: String
) : AppUpdater {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    override fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = runCatching {
                val response = client.get("https://api.github.com/repos/$UPDATE_REPO/releases/tags/$UPDATE_RELEASE_TAG") {
                    // GitHub rechaza peticiones a su API sin User-Agent.
                    header(HttpHeaders.UserAgent, "LocalChatBot/$currentVersion")
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                }
                // Un repo privado responde 404 sin token: no es un error que valga la pena
                // enseñar, simplemente no hay de dónde actualizar.
                if (!response.status.isSuccess()) return@runCatching UpdateState.UpToDate
                val release = json.parseToJsonElement(response.bodyAsText()) as JsonObject
                pickUpdate(release, currentVersion) ?: UpdateState.UpToDate
            }.getOrElse { UpdateState.Error("No se pudo comprobar: ${it.message}") }
        }
    }

    override fun install() {
        val update = _state.value as? UpdateState.Available ?: return
        _state.value = UpdateState.Downloading(update.version)
        scope.launch {
            val result = runCatching {
                val target = withContext(Dispatchers.IO) {
                    File(System.getProperty("java.io.tmpdir"), update.fileName).also { it.delete() }
                }
                // prepareGet + canal: el MSI pesa decenas de MB y no tiene por qué pasar entero
                // por memoria. El cliente sigue la redirección al CDN de GitHub.
                client.prepareGet(update.downloadUrl) {
                    header(HttpHeaders.UserAgent, "LocalChatBot/$currentVersion")
                }.execute { response ->
                    if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                    withContext(Dispatchers.IO) {
                        target.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
                    }
                }
                // msiexec muestra su propio asistente; la app tiene que estar cerrada para que
                // pueda reemplazar sus archivos, así que se sale en cuanto arranca. El hook de
                // cierre de main.kt vuelca settings y chats antes.
                ProcessBuilder("msiexec", "/i", target.absolutePath).start()
            }
            result.fold(
                onSuccess = { exitProcess(0) },
                onFailure = { _state.value = UpdateState.Error("No se pudo descargar la actualización: ${it.message}") }
            )
        }
    }
}
