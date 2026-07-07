package com.localchatbot.core.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.localchatbot.data.local.db.LocalChatBotDatabase
import java.io.File
import java.time.Instant
import java.time.ZoneId

actual object DatabaseDriverFactory {
    actual fun create(): SqlDriver {
        val appDir = File(System.getProperty("user.home"), ".localchatbot")
        appDir.mkdirs()
        val dbFile = File(appDir, "localchatbot.db")
        val isNew = !dbFile.exists()
        if (!isNew) backupDailyIfStale(dbFile)
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (isNew) {
            LocalChatBotDatabase.Schema.create(driver)
        }
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
        return driver
    }

    /**
     * Copia `localchatbot.db` a `localchatbot.db.bak` como mucho una vez por día calendario
     * (comparando fechas, no timestamps exactos), antes de abrir la conexión — sin escritores
     * concurrentes en ese instante, la copia es consistente. Backup de bajo costo ante
     * corrupción de disco, no un historial de versiones.
     */
    private fun backupDailyIfStale(dbFile: File) {
        runCatching {
            val backup = File(dbFile.parentFile, "${dbFile.name}.bak")
            val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
            val backupDay = if (backup.exists()) {
                Instant.ofEpochMilli(backup.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
            } else null
            if (backupDay != today) {
                dbFile.copyTo(backup, overwrite = true)
            }
        }
    }
}
