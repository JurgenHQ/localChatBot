package com.localchatbot.core.storage.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import java.io.File

/**
 * Versión que se asume para una base preexistente sin `user_version` estampado.
 *
 * Android e iOS nunca tuvieron este problema: sus drivers reciben el `SqlSchema` y delegan
 * en los callbacks del motor (`onCreate`/`onUpgrade`), que estampan y migran solos. El
 * driver JDBC no gestiona la versión: había que hacerlo a mano y no se hacía, así que toda
 * base de desktop creada hasta ahora quedó en `user_version = 0` **aunque su contenido ya
 * es el esquema v1**. Migrar "desde 0" volvería a aplicarle migraciones que ya tiene.
 */
private const val BASELINE_VERSION = 1L

/**
 * Deja la base en la versión de [schema], creándola o migrándola según haga falta.
 *
 * Tres caminos:
 * - **Base nueva** → `create()` + estampar la versión.
 * - **Base preexistente sin versión** (`user_version = 0`) → se *adopta* como
 *   [BASELINE_VERSION]: solo se estampa el pragma, no se toca ni una fila. A partir de ahí
 *   sigue el camino normal por si hay migraciones pendientes.
 * - **Base versionada** → `migrate()` de su versión a la de [schema].
 *
 * Antes de migrar se guarda una copia consistente (ver [backupBeforeMigration]). Y aunque
 * el mecanismo ya es correcto, [ensureMessageSortIndex] se mantiene aparte: el índice es
 * anterior al versionado, así que una base adoptada como v1 puede no tenerlo.
 */
fun migrateOrCreate(driver: SqlDriver, schema: SqlSchema<QueryResult.Value<Unit>>, dbFile: File, isNew: Boolean) {
    if (isNew) {
        schema.create(driver)
        driver.setUserVersion(schema.version)
        return
    }

    // Antes de adoptar nada: el índice es anterior al versionado, así que una base vieja
    // puede no tenerlo — y la v1 "de verdad" (el snapshot `databases/1.db` contra el que se
    // verifican las migraciones) sí lo incluye. Aplicarlo aquí hace que la base coincida con
    // esa v1 antes de estamparla como tal, en vez de dar por buena una versión que miente.
    ensureMessageSortIndex(driver)

    val stored = driver.userVersion()
    val from = if (stored == 0L) {
        driver.setUserVersion(BASELINE_VERSION)
        BASELINE_VERSION
    } else {
        stored
    }

    // `>` en vez de `!=`: si la base viene de una versión MÁS nueva (el usuario abrió una
    // build posterior y volvió atrás) migrar hacia abajo no existe. Se deja como está y se
    // deja que fallen las consultas que no encajen, que es más honesto que corromperla.
    if (from >= schema.version) return

    backupBeforeMigration(driver, dbFile, from)
    schema.migrate(driver, from, schema.version)
    driver.setUserVersion(schema.version)
    compact(driver)
}

/**
 * Devuelve al disco el espacio que la migración haya dejado libre.
 *
 * Borrar filas no encoge el archivo: SQLite marca las páginas como reutilizables y las
 * conserva. La limpieza de huérfanos de `2.sqm` elimina el 94% del contenido en bases reales,
 * así que sin esto el archivo seguiría pesando lo mismo indefinidamente.
 *
 * Va **fuera** de la migración a propósito: `VACUUM` no puede ejecutarse dentro de una
 * transacción, y `schema.migrate` corre en una. Es best-effort: reclamar espacio nunca debe
 * impedir que la app arranque. El coste es el de reescribir el archivo una vez, comparable al
 * respaldo que se acaba de hacer, y solo ocurre cuando hubo migración.
 */
private fun compact(driver: SqlDriver) {
    runCatching { driver.execute(identifier = null, sql = "VACUUM;", parameters = 0) }
}

/**
 * Copia la base antes de migrarla, a `localchatbot.db.v<versión>.bak`.
 *
 * Usa `VACUUM INTO` y no una copia de archivo: la base está en modo WAL, así que parte de
 * lo confirmado puede vivir en el `-wal` todavía sin checkpointear y copiar solo el `.db`
 * perdería justo eso. `VACUUM INTO` lo resuelve dentro de SQLite y escribe un archivo ya
 * consistente.
 *
 * El nombre lleva la versión de origen para que una segunda migración no pise el respaldo
 * de la primera. Si el archivo ya existe se borra: `VACUUM INTO` falla si el destino está
 * ocupado, y que exista significa que esa migración ya se intentó antes.
 *
 * Un fallo aquí **no** aborta la migración (va en `runCatching`): el respaldo es una red de
 * seguridad, no un requisito — en un disco lleno preferimos migrar sin copia a dejar la app
 * inservible. La copia diaria de `DatabaseDriverFactory` sigue existiendo por su cuenta.
 */
private fun backupBeforeMigration(driver: SqlDriver, dbFile: File, fromVersion: Long) {
    runCatching {
        val backup = File(dbFile.parentFile, "${dbFile.name}.v$fromVersion.bak")
        if (backup.exists()) backup.delete()
        // Ruta entre comillas simples y escapando las que pudiera traer: el literal va
        // inline porque VACUUM INTO no admite parámetros vinculados.
        val target = backup.absolutePath.replace("'", "''")
        driver.execute(identifier = null, sql = "VACUUM INTO '$target';", parameters = 0)
    }
}

/** Lee `PRAGMA user_version`. Devuelve 0 si la base nunca lo estampó. */
private fun SqlDriver.userVersion(): Long = executeQuery(
    identifier = null,
    sql = "PRAGMA user_version;",
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
    parameters = 0
).value

/** `PRAGMA user_version` no admite parámetros vinculados, de ahí la interpolación (de un Long). */
private fun SqlDriver.setUserVersion(version: Long) {
    execute(identifier = null, sql = "PRAGMA user_version = $version;", parameters = 0)
}
