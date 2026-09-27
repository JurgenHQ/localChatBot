package com.localchatbot.data.repository.legacy

import com.localchatbot.domain.model.ChatSession
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

/**
 * Lector del historial en el formato antiguo sobre `multiplatform-settings`
 * (XML/SharedPreferences/NSUserDefaults), previo a la migración a SQLDelight.
 *
 * **Ya no es un `ChatRepository`.** Lo fue mientras se retiraba de DI, pero implementar el
 * contrato entero obligaba a arrastrar ~330 líneas de escritura (mutaciones, throttle de
 * persistencia, flows derivados) que nadie llamaba, y a añadir un miembro nuevo cada vez que
 * cambiaba la interfaz. Su única razón de existir es [load], la fuente de
 * [com.localchatbot.data.migration.ChatHistoryMigration]: leer una vez lo que había y
 * volcarlo a SQLite.
 *
 * No escribe nada — salvo [migrateLegacyIfNeeded], que normaliza el formato aún más viejo
 * (toda la lista en una sola clave) al de clave-por-sesión antes de leerlo.
 */
class LegacySettingsChatRepository(
    private val settings: Settings,
    private val json: Json
) {

    /**
     * Carga el índice de IDs y cada sesión desde su propia clave. Si existe la
     * clave legacy (toda la lista en un único JSON, formato anterior), migra a
     * clave-por-sesión y elimina la clave vieja — solo cuando la decodificación
     * tuvo éxito, para no destruir datos ante un JSON corrupto.
     */
    fun load(): List<ChatSession> {
        migrateLegacyIfNeeded()
        val idsRaw = settings.getStringOrNull(KEY_SESSION_IDS) ?: return emptyList()
        val ids = runCatching { json.decodeFromString(IdsSerializer, idsRaw) }.getOrDefault(emptyList())
        return ids.mapNotNull { id ->
            settings.getStringOrNull(sessionKey(id))?.let { raw ->
                runCatching { json.decodeFromString(ChatSession.serializer(), raw) }.getOrNull()
            }
        }
    }

    private fun migrateLegacyIfNeeded() {
        val raw = settings.getStringOrNull(KEY_SESSIONS_LEGACY) ?: return
        val list = runCatching { json.decodeFromString(SessionsSerializer, raw) }.getOrNull() ?: return
        runCatching {
            list.forEach { session ->
                settings.putString(sessionKey(session.id), json.encodeToString(ChatSession.serializer(), session))
            }
            settings.putString(KEY_SESSION_IDS, json.encodeToString(IdsSerializer, list.map { it.id }))
            settings.remove(KEY_SESSIONS_LEGACY)
        }
    }

    private fun sessionKey(id: String): String = "$KEY_SESSION_PREFIX$id"

    private companion object {
        const val KEY_SESSIONS_LEGACY = "chat_sessions"
        const val KEY_SESSION_IDS = "chat_session_ids"
        const val KEY_SESSION_PREFIX = "chat_session_"
        val SessionsSerializer = kotlinx.serialization.builtins.ListSerializer(ChatSession.serializer())
        val IdsSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
    }
}
