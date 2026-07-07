package com.localchatbot.core.storage

/**
 * Copia el `settings.xml` (u equivalente por plataforma) a un backup fijo justo antes de la
 * migración one-shot de historial de chat a SQLDelight. No-op fuera de desktop: Android/iOS
 * no tienen un archivo plano equivalente accesible de la misma forma (SharedPreferences/
 * NSUserDefaults), así que ahí la red de seguridad es no borrar las claves legacy.
 */
expect fun backupSettingsBeforeChatMigration()
