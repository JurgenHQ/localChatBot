package com.localchatbot.core.storage

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.util.Properties

actual object SettingsFactory {
    actual fun create(): Settings {
        val appDir = File(System.getProperty("user.home"), ".localchatbot")
        appDir.mkdirs()
        val file = File(appDir, "settings.xml")
        val props = Properties()
        if (file.exists()) runCatching {
            file.inputStream().use { props.loadFromXML(it) }
        }
        return PropertiesSettings(props) { updated ->
            runCatching { file.outputStream().use { updated.storeToXML(it, null, "UTF-8") } }
        }
    }
}
