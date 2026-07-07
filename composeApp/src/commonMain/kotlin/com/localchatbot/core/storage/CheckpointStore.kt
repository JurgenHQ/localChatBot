package com.localchatbot.core.storage

/**
 * Checkpoints por turno del agente: antes de que una tool de mutación toque un
 * archivo, se snapshotea su estado previo bajo
 * `~/.localchatbot/checkpoints/<sessionId>/<turnId>/`. "Revertir este turno"
 * restaura los archivos a ese estado (los mensajes del chat se conservan).
 *
 * Implementación real solo en desktop (donde viven las fs tools); en móvil los
 * métodos son no-op y el chip de revert nunca aparece (checkpointId nunca se setea).
 *
 * Limitación conocida: solo cubre las tools de archivos (`create_file`, `edit_file`,
 * `multi_edit`, `delete_file`, `create_directory`, `save_image`). Mutaciones vía
 * `run_command`, tools MCP o scripts de skills NO se capturan.
 */
expect class CheckpointStore() {

    /**
     * Snapshotea el estado previo de [absPath] para el turno [turnId], si aún no
     * fue snapshoteado en ese turno (idempotente por path: el estado que vale es
     * el de ANTES de la primera mutación del turno).
     */
    suspend fun snapshotBeforeMutation(sessionId: String, turnId: String, absPath: String, toolName: String)

    /** True si existe un checkpoint no vacío para el turno. */
    suspend fun hasCheckpoint(sessionId: String, turnId: String): Boolean

    /** Paths tocados en el turno (para el diálogo de confirmación del revert). */
    suspend fun checkpointSummary(sessionId: String, turnId: String): List<String>

    /**
     * Restaura los archivos del turno a su estado previo: lo creado se borra,
     * lo editado/borrado se restaura byte a byte. Errores por archivo se
     * acumulan sin abortar el resto.
     */
    suspend fun revert(sessionId: String, turnId: String): CheckpointRevertResult

    /** Elimina todos los checkpoints de una sesión (al borrar la sesión). */
    suspend fun deleteSession(sessionId: String)

    /** Conserva solo los últimos [keepLastTurns] turnos con checkpoint de la sesión. */
    suspend fun pruneSession(sessionId: String, keepLastTurns: Int = 10)
}

data class CheckpointRevertResult(
    val restored: List<String>,
    val errors: List<String>
)
