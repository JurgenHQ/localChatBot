package com.localchatbot.core.storage

/**
 * Stub: en iOS las fs tools no existen, así que nunca hay checkpoints.
 */
actual class CheckpointStore {
    actual suspend fun snapshotBeforeMutation(sessionId: String, turnId: String, absPath: String, toolName: String) = Unit

    actual suspend fun hasCheckpoint(sessionId: String, turnId: String): Boolean = false

    actual suspend fun checkpointSummary(sessionId: String, turnId: String): List<String> = emptyList()

    actual suspend fun revert(sessionId: String, turnId: String): CheckpointRevertResult =
        CheckpointRevertResult(restored = emptyList(), errors = listOf("Checkpoints no disponibles en iOS"))

    actual suspend fun deleteSession(sessionId: String) = Unit

    actual suspend fun pruneSession(sessionId: String, keepLastTurns: Int) = Unit
}
