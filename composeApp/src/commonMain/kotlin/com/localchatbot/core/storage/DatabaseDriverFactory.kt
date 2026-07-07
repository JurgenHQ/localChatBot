package com.localchatbot.core.storage

import app.cash.sqldelight.db.SqlDriver

expect object DatabaseDriverFactory {
    fun create(): SqlDriver
}
