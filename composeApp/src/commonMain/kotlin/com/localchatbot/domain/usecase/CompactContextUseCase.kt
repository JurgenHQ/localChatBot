package com.localchatbot.domain.usecase

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.CompactBoundary
import com.localchatbot.domain.model.Role
import kotlinx.datetime.Clock
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository

/**
 * Compactación **manual** del contexto (`/compact`).
 *
 * El resumen rodante que ya existía es automático y reactivo: solo entra cuando la ventana
 * de contexto se desbordó y hubo que descartar mensajes, y el usuario no lo ve ni lo
 * decide. Esto es lo contrario: se dispara cuando querés, muestra el resumen antes de
 * aplicarlo y te deja editarlo.
 *
 * El flujo es en dos pasos a propósito:
 * 1. [preview] genera el resumen y **no toca nada**. Si el modelo escribe algo pobre o
 *    equivocado, cancelar no cuesta nada.
 * 2. [apply] persiste el texto (posiblemente editado a mano) y fija el corte.
 *
 * Aplicar **no borra mensajes**: siguen en la base y en pantalla. Lo único que cambia es
 * que dejan de enviarse al modelo, que a partir de ahí los ve como el resumen. Por eso el
 * corte vive en preferencias (`sessionCompactBoundaries`) y no en la tabla de mensajes.
 */
class CompactContextUseCase(
    private val chats: ChatRepository,
    private val model: ModelRepository,
    private val prefs: PreferencesRepository
) {

    data class Preview(
        /** Resumen propuesto por el modelo, editable antes de aplicarlo. */
        val summary: String,
        /** Último mensaje que quedará representado por el resumen. */
        val boundaryMessageId: String,
        /** Cuántos mensajes visibles (sin contar los `Tool`) cubre el resumen. */
        val messageCount: Int,
        /** Ahorro estimado en tokens (≈ chars/4), para poder decidir si vale la pena. */
        val estimatedTokensFreed: Int
    )

    /**
     * Genera el resumen del tramo compactable de [sessionId] sin persistir nada.
     *
     * Deja fuera los [KEEP_RECENT] mensajes más nuevos: compactar *todo*, incluido el
     * intercambio en curso, obligaría al modelo a trabajar solo con un resumen de lo que
     * acaba de decir, que es justo el contexto que todavía importa palabra por palabra.
     */
    suspend fun preview(sessionId: String): Result<Preview> {
        val session = chats.getSession(sessionId)
            ?: return Result.failure(IllegalStateException("La conversación ya no existe"))

        val previousBoundary = prefs.current().sessionCompactBoundaries[sessionId]
        val history = session.messages.filter { it.role != Role.System }
        // Arrancar donde terminó la compactación anterior: lo de antes ya está en el resumen.
        val startIdx = previousBoundary
            ?.let { b -> history.indexOfFirst { it.id == b.messageId } }
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        val compactable = history.drop(startIdx).dropLast(KEEP_RECENT)
        if (compactable.size < MIN_MESSAGES) {
            return Result.failure(
                IllegalStateException(
                    "No hay suficiente historial nuevo para compactar (hacen falta al menos " +
                        "$MIN_MESSAGES mensajes por encima de los $KEEP_RECENT más recientes)."
                )
            )
        }

        val cfg = prefs.current().connection
        if (!cfg.isValid()) return Result.failure(IllegalStateException("Sin conexión configurada"))

        val transcript = capTranscript(buildSummaryTranscript(session.contextSummary, compactable))
        val summary = model.summarize(cfg.baseUrl(), cfg.model, transcript)
            ?: return Result.failure(IllegalStateException("El modelo no devolvió un resumen"))

        return Result.success(
            Preview(
                summary = summary,
                boundaryMessageId = compactable.last().id,
                messageCount = compactable.count { it.role != Role.Tool },
                estimatedTokensFreed = estimateTokens(compactable) - (summary.length + 3) / 4
            )
        )
    }

    /**
     * Persiste [summary] como resumen de la sesión y fija el corte. A partir de acá,
     * `buildMessagesForApi` deja de mandar todo lo anterior a [boundaryMessageId].
     */
    suspend fun apply(sessionId: String, summary: String, boundaryMessageId: String): Result<Unit> =
        runCatching {
            chats.updateContextSummary(sessionId, summary.trim())
            prefs.updateSessionCompactBoundary(
                sessionId,
                CompactBoundary(
                    messageId = boundaryMessageId,
                    // Marca de tiempo para que la barra de contexto sepa qué mediciones del
                    // servidor son posteriores al corte (las anteriores contaban de más).
                    appliedAtEpochMs = Clock.System.now().toEpochMilliseconds()
                )
            )
        }

    /** Deshace la compactación: el historial completo vuelve a enviarse al modelo. */
    suspend fun undo(sessionId: String): Result<Unit> = runCatching {
        prefs.updateSessionCompactBoundary(sessionId, null)
    }

    private fun estimateTokens(messages: List<ChatMessage>): Int =
        (messages.sumOf { it.content.length } + 3) / 4

    /**
     * `ModelRepository.summarize` recorta el transcript a 8k chars por su cuenta. Recortar
     * acá primero, conservando cabeza **y** cola, evita que una conversación larga se
     * resuma usando solo su principio: la tarea original está al inicio y el estado actual
     * al final, y perder cualquiera de los dos deja un resumen inútil.
     */
    private fun capTranscript(transcript: String): String {
        if (transcript.length <= TRANSCRIPT_CAP) return transcript
        val head = transcript.take(TRANSCRIPT_HEAD)
        val tail = transcript.takeLast(TRANSCRIPT_CAP - TRANSCRIPT_HEAD)
        return "$head\n[… tramo intermedio omitido …]\n$tail"
    }

    private companion object {
        /**
         * Mensajes recientes que nunca se compactan. 6 ≈ los últimos tres intercambios.
         */
        const val KEEP_RECENT = 6

        /** Por debajo de esto no hay nada que ganar y sí una llamada al modelo que pagar. */
        const val MIN_MESSAGES = 4

        /** Tope alineado con el que aplica `ModelRepositoryImpl.summarize`. */
        const val TRANSCRIPT_CAP = 8_000
        const val TRANSCRIPT_HEAD = 4_000
    }
}
