package com.localchatbot.core.storage

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

actual object SettingsFactory {
    actual fun create(): Settings {
        val prefs = Preferences.userRoot().node("com.localchatbot")
        return PreferencesSettings(prefs)
    }
}
