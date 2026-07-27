package com.localchatbot.core.storage

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

actual object SettingsFactory {

    /**
     * Hilo escritor único (daemon). Ver [scheduleWrite]: las escrituras salen del hilo que
     * llama a `settings.putX(...)` — que en Desktop es el EDT, porque `viewModelScope` usa
     * `Dispatchers.Main.immediate` — y se serializan aquí.
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "settings-writer").apply { isDaemon = true }
    }

    /** Último snapshot pendiente de escribir; los intermedios se descartan (coalescing). */
    private val pending = AtomicReference<Pair<File, Properties>?>(null)

    actual fun create(): Settings {
        val appDir = File(System.getProperty("user.home"), ".localchatbot")
        appDir.mkdirs()
        val file = File(appDir, "settings.xml")
        val props = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.loadFromXML(it) } }
                .onFailure { e ->
                    System.err.println(
                        "[SettingsFactory] fallo leyendo settings.xml (arrancando con preferencias vacías): ${e.message}"
                    )
                }
        }
        return PropertiesSettings(props) { updated -> scheduleWrite(file, updated) }
    }

    /**
     * `PropertiesSettings` invoca este callback **sincrónicamente en cada `putX`**, y casi
     * todas las escrituras de preferencias salen de un `viewModelScope.launch` (EDT en
     * Desktop): escribir el XML ahí bloqueaba el hilo de UI en cada toggle. En macOS el
     * coste es despreciable, pero en Windows (NTFS + Defender inspeccionando el .tmp, el
     * .bak y el rename) se nota como un tirón al pulsar los botones. Aquí solo se hace la
     * copia en memoria — barata — y el disco queda para el hilo escritor.
     *
     * La copia se toma bajo el monitor del propio `Properties` (es un `Hashtable`, sus
     * métodos sincronizan sobre `this`) para que un `putX` desde otro hilo no la rompa con
     * `ConcurrentModificationException` a mitad del recorrido.
     *
     * Coalescing: si llegan varias escrituras seguidas (p. ej. `updateRemoteAccess`, que
     * son tres `putX`), cada tarea toma el snapshot **más reciente** y las anteriores se
     * quedan sin trabajo. Nunca se escribe un estado intermedio más nuevo que el último.
     *
     * A cambio, una escritura puede perderse si el proceso muere de golpe en los pocos ms
     * siguientes; el cierre normal la fuerza con [flushPendingWrites].
     */
    private fun scheduleWrite(file: File, updated: Properties) {
        val snapshot = synchronized(updated) { Properties().apply { putAll(updated) } }
        pending.set(file to snapshot)
        runCatching { writer.execute(::drainPending) }
    }

    private fun drainPending() {
        val (file, props) = pending.getAndSet(null) ?: return
        writeAtomically(file, props)
    }

    /**
     * Fuerza la escritura pendiente y espera a que termine. Se llama desde el shutdown hook
     * de Desktop: el hilo escritor es daemon, así que sin esto la última preferencia
     * cambiada antes de cerrar podría no llegar al disco.
     */
    fun flushPendingWrites(timeoutMs: Long = 3_000) {
        val task = runCatching { writer.submit(Runnable { drainPending() }) }.getOrNull() ?: return
        runCatching { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * Escribe a un archivo temporal y renombra con `ATOMIC_MOVE`: si el proceso muere a
     * mitad de la escritura (crash, kill al instalar encima), `settings.xml` sigue siendo
     * la última versión buena — nunca queda vacío/truncado a medias. Antes de reemplazar,
     * respalda la versión anterior en `.bak` (rotación de 1 nivel, recuperación manual).
     */
    private fun writeAtomically(file: File, updated: Properties) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { updated.storeToXML(it, null, "UTF-8") }
            if (file.exists()) {
                runCatching { file.copyTo(File(file.parentFile, "${file.name}.bak"), overwrite = true) }
            }
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { e ->
            System.err.println("[SettingsFactory] fallo escribiendo settings.xml: ${e.message}")
        }
    }
}
