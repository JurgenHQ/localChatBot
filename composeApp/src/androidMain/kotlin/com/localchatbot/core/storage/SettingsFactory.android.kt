package com.localchatbot.core.storage

import android.content.Context
import com.localchatbot.AppContextHolder
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

actual object SettingsFactory {
    actual fun create(): Settings {
        val ctx: Context = AppContextHolder.context
        val prefs = ctx.getSharedPreferences("local_chatbot_prefs", Context.MODE_PRIVATE)
        return SharedPreferencesSettings(prefs)
    }
}
