package com.localchatbot.core.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.localchatbot.AppContextHolder
import com.localchatbot.data.local.db.LocalChatBotDatabase

actual object DatabaseDriverFactory {
    actual fun create(): SqlDriver {
        val ctx: Context = AppContextHolder.context
        return AndroidSqliteDriver(
            schema = LocalChatBotDatabase.Schema,
            context = ctx,
            name = "localchatbot.db",
            callback = object : AndroidSqliteDriver.Callback(LocalChatBotDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=ON;")
                }
            }
        )
    }
}
