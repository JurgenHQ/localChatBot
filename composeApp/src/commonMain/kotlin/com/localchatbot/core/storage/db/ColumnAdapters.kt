package com.localchatbot.core.storage.db

import app.cash.sqldelight.ColumnAdapter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Adapta columnas SQLite `TEXT` a tipos Kotlin serializables vía JSON (listas anidadas, objetos). */
class JsonColumnAdapter<T : Any>(
    private val json: Json,
    private val serializer: KSerializer<T>
) : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = json.decodeFromString(serializer, databaseValue)
    override fun encode(value: T): String = json.encodeToString(serializer, value)
}

/** Adapta columnas SQLite `TEXT` a un `enum class` Kotlin vía su nombre. */
class EnumColumnAdapter<T : Enum<T>>(private val valueOf: (String) -> T) : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T = valueOf(databaseValue)
    override fun encode(value: T): String = value.name
}
