package com.localchatbot.core.storage

actual fun backupSettingsBeforeChatMigration() {
    // No-op: NSUserDefaults no es un archivo plano copiable de la misma forma.
}
