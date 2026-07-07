package com.localchatbot.core.storage

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

actual object SettingsFactory {
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
        return PropertiesSettings(props) { updated -> writeAtomically(file, updated) }
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
