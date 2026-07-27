package com.localchatbot.core.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.localchatbot.core.storage.db.ensureMessageSortIndex
import com.localchatbot.data.local.db.LocalChatBotDatabase

actual object DatabaseDriverFactory {
    actual fun create(): SqlDriver {
        val driver: SqlDriver = NativeSqliteDriver(LocalChatBotDatabase.Schema, "localchatbot.db")
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        ensureMessageSortIndex(driver)
        return driver
    }
}
