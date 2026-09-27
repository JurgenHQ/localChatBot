package com.localchatbot.core.storage

import java.io.File

actual fun backupSettingsBeforeChatMigration() {
    runCatching {
        val appDir = File(System.getProperty("user.home"), ".localchatbot")
        val source = File(appDir, "settings.xml")
        if (source.exists()) {
            source.copyTo(File(appDir, "settings.xml.pre-sqldelight-migration.bak"), overwrite = true)
        }
    }
}
