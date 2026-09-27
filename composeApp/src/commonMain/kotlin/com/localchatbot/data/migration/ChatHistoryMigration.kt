package com.localchatbot.data.migration

import com.localchatbot.data.local.db.LocalChatBotDatabase
import com.localchatbot.data.repository.legacy.LegacySettingsChatRepository
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

/**
 * Migración one-shot del historial de chat desde `multiplatform-settings` (formato legacy,
 * ver [LegacySettingsChatRepository]) a SQLite vía SQLDelight. Corre en una sola transacción:
 * si algo falla a mitad, rollback completo, no queda estado a medias. El caller decide cuándo
 * marcar la migración como completada (solo si [migrateIfNeeded] devuelve `true`).
 */
class ChatHistoryMigration(
    private val settings: Settings,
    private val db: LocalChatBotDatabase,
    private val json: Json
) {
    suspend fun migrateIfNeeded(alreadyMigrated: Boolean): Boolean {
        if (alreadyMigrated) return false

        // Guarda contra reintentos: si ya hay sesiones en SQLite (p.ej. el flag de éxito
        // no llegó a persistirse por una escritura concurrente de otra instancia de la app
        // sobre el mismo settings.xml, pero la migración en sí ya insertó los datos), no
        // reinsertar — violaría la PK de `session.id` y fallaría la transacción en cada
        // arranque. Tratamos "ya hay datos" como "ya migrado".
        val alreadyHasData = runCatching { db.sessionQueries.selectAllSessions().executeAsList().isNotEmpty() }
            .getOrDefault(false)
        if (alreadyHasData) return true

        val sessions = runCatching { LegacySettingsChatRepository(settings, json).load() }
            .getOrElse { e ->
                println("[ChatHistoryMigration] fallo leyendo historial legacy: ${e.message}")
                return false
            }
        if (sessions.isEmpty()) return true

        return runCatching {
            db.transaction {
                sessions.forEach { session ->
                    db.sessionQueries.insertSession(
                        id = session.id,
                        title = session.title,
                        model = session.model,
                        created_at_epoch_ms = session.createdAtEpochMs,
                        updated_at_epoch_ms = session.updatedAtEpochMs,
                        pinned = session.pinned,
                        generation_params = session.generationParams,
                        context_summary = session.contextSummary
                    )
                    session.messages.forEachIndexed { index, message ->
                        db.messageQueries.insertMessage(
                            id = message.id,
                            session_id = session.id,
                            role = message.role,
                            content = message.content,
                            timestamp_epoch_ms = message.timestampEpochMs,
                            sort_order = index.toLong(),
                            attachments = message.attachments,
                            tool_calls = message.toolCalls,
                            tool_call_id = message.toolCallId,
                            tool_name = message.toolName,
                            sources = message.sources,
                            reasoning = message.reasoning,
                            metrics = message.metrics,
                            checkpoint_id = message.checkpointId,
                            // El formato legacy nunca guardó el modelo por mensaje: queda
                            // null y quien lo lea cae a `session.model`.
                            model = null
                        )
                    }
                }
            }
        }.onFailure { e ->
            println("[ChatHistoryMigration] fallo insertando historial en SQLite, rollback aplicado: ${e.message}")
        }.isSuccess
    }
}
