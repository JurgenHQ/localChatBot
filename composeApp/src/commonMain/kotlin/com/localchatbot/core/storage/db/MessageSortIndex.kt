package com.localchatbot.core.storage.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Crea `message_session_sort_idx` si falta, en cada apertura de la base.
 *
 * El índice está declarado en `Message.sq`, pero eso solo cubre las bases **nuevas**:
 * `Schema.create()` corre una única vez. Las bases ya existentes no lo recibirían nunca,
 * porque este proyecto no tiene migraciones (`.sqm`) y —más importante— el driver de
 * desktop ni siquiera llama a `Schema.migrate()`: solo hace `Schema.create()` cuando el
 * archivo no existe (ver `DatabaseDriverFactory.desktop.kt`). Sin esto, cualquier usuario
 * con historial previo se quedaría sin el índice del que depende el preview de
 * `selectAllSessionSummaries` para no escanear todos los mensajes.
 *
 * `IF NOT EXISTS` lo hace idempotente y barato: si ya está, SQLite no hace nada.
 *
 * No sustituye a un sistema de migraciones de verdad — si algún día hace falta cambiar el
 * esquema (columnas, tablas), hay que arreglar antes el driver de desktop.
 */
fun ensureMessageSortIndex(driver: SqlDriver) {
    driver.execute(
        identifier = null,
        sql = "CREATE INDEX IF NOT EXISTS message_session_sort_idx ON message(session_id, sort_order);",
        parameters = 0
    )
}
