package com.localchatbot.core.storage

actual fun backupSettingsBeforeChatMigration() {
    // No-op: SharedPreferences no es un archivo plano copiable de la misma forma.
}
