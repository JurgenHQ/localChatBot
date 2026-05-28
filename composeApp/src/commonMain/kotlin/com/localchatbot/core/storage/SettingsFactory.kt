package com.localchatbot.core.storage

import com.russhwolf.settings.Settings

expect object SettingsFactory {
    fun create(): Settings
}
