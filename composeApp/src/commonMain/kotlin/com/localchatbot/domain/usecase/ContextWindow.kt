package com.localchatbot.domain.usecase

import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.Role

/**
 * Lógica pura (sin repos ni red) de cómo se arma la petición al modelo a partir del
 * historial: compactación manual, ventana deslizante con histéresis y montaje final
 * del system + contexto efímero. Vive aparte de [SendMessageUseCase] para poder
 * testearla sin montar el caso de uso entero.
 *
 * Todo gira alrededor del **KV-cache** de llama.cpp / LM Studio / Ollama: el servidor
 * reutiliza el prefijo idéntico más largo entre la petición anterior y la nueva, y
 * reprocesa todo lo que viene después del primer carácter distinto. Con 60–80k tokens
 * de historial y un prefill de 500–2000 tok/s, invalidar el prefijo cuesta de 30 s a
 * 2 min antes del primer token. De ahí las tres reglas:
 *
 * 1. El system (posición 0) solo lleva lo **estable** entre turnos.
 * 2. Lo volátil del turno (bloque `<workspace>` con git status/árbol) va como prefijo
 *    del **último** mensaje `user`: al cambiar solo invalida desde ese mensaje.
 * 3. El nudge efímero va al **final** de la petición: no invalida nada.
 *
 * Y la ventana recorta con histéresis para que el primer mensaje del historial no se
 * mueva en cada turno (ver [window]).
 */
internal object ContextWindow {

    /**
     * Al pasarse del presupuesto, la ventana se recorta hasta esta fracción de él, no
     * justo por debajo. Así queda margen para muchos turnos con el mismo inicio de
     * ventana (= mismo prefijo = cache reutilizado) en vez de mover el corte un mensaje
     * en cada turno, que invalidaba el cache de toda la conversación cada vez.
     */
    const val HYSTERESIS_TARGET_FRACTION = 0.5

    /** Id del mensaje sintético que lleva el nudge cuando no hay `user` al final. */
    const val NUDGE_MESSAGE_ID = "ephemeral-nudge"

    data class Compacted(val history: List<ChatMessage>, val compacted: Boolean)

    data class Window(
        /** Mensajes que entran en la petición, en orden. */
        val windowed: List<ChatMessage>,
        /** Mensajes que quedaron fuera por presupuesto (vacío si no hubo recorte). */
        val discarded: List<ChatMessage>,
        /** Id del primer mensaje de la ventana si hubo recorte; null si entra todo. */
        val startId: String?
    )

    /**
     * Compactación manual: descarta todo hasta [compactedThroughId] inclusive. Si el id
     * ya no existe (el usuario reenvió un mensaje anterior y truncó la sesión) el corte
     * se ignora y se usa el historial completo — degradar a "no compactado" es seguro,
     * enviar de menos no lo sería.
     */
    fun applyCompaction(fullHistory: List<ChatMessage>, compactedThroughId: String?): Compacted {
        val boundaryIdx = compactedThroughId?.let { id -> fullHistory.indexOfFirst { it.id == id } } ?: -1
        if (boundaryIdx < 0) return Compacted(fullHistory, compacted = false)
        // dropWhile Tool: si el corte cae entre un assistant que anunció tools y sus
        // resultados, la ventana arrancaría con `role=tool` huérfanos y el servidor
        // rechaza la request.
        return Compacted(fullHistory.drop(boundaryIdx + 1).dropWhile { it.role == Role.Tool }, compacted = true)
    }

    /**
     * Ventana por presupuesto de tokens estimados, con histéresis.
     *
     * - Si todo el historial cabe en [budget], entra entero.
     * - Si hay un corte anterior ([previousStartId]) que sigue existiendo y lo que va
     *   desde él hasta el final aún cabe, se **mantiene** ese corte: el prefijo de la
     *   petición no cambia y el servidor reutiliza el cache.
     * - Si no, se hace un corte nuevo que deja la ventana en ~[HYSTERESIS_TARGET_FRACTION]
     *   del presupuesto, para que el siguiente corte tarde en volver a hacer falta.
     *
     * El mensaje más reciente entra siempre, aunque reviente el presupuesto — sin él la
     * petición no tiene sentido. La ventana nunca arranca con resultados de tool
     * huérfanos: si el corte cae sobre uno, se retrocede hasta incluir el assistant que
     * lo anunció (antes se avanzaba, y un único resultado de tool enorme al final dejaba
     * la ventana vacía).
     */
    fun window(
        history: List<ChatMessage>,
        budget: Int,
        previousStartId: String?,
        estimate: (ChatMessage) -> Int
    ): Window {
        if (history.isEmpty()) return Window(emptyList(), emptyList(), null)
        val costs = history.map(estimate)
        val total = costs.sum()
        if (total <= budget) return Window(history, emptyList(), null)

        val previousIdx = previousStartId?.let { id -> history.indexOfFirst { it.id == id } } ?: -1
        if (previousIdx >= 0 && costs.subList(previousIdx, costs.size).sum() <= budget) {
            return split(history, previousIdx)
        }

        val target = (budget * HYSTERESIS_TARGET_FRACTION).toInt()
        var used = 0
        var start = history.size
        while (start > 0) {
            val t = costs[start - 1]
            if (used + t > target && start < history.size) break
            used += t
            start--
        }
        // Retroceder hasta un mensaje que no sea `role=tool` (el assistant anunciador).
        var aligned = start
        while (aligned > 0 && history[aligned].role == Role.Tool) aligned--
        if (history[aligned].role == Role.Tool) {
            // No hay anunciador antes (no debería pasar tras applyCompaction): último
            // recurso, avanzar hasta el primer mensaje que no sea tool.
            aligned = start
            while (aligned < history.size - 1 && history[aligned].role == Role.Tool) aligned++
        }
        return split(history, aligned)
    }

    private fun split(history: List<ChatMessage>, startIdx: Int): Window =
        if (startIdx <= 0) {
            Window(history, emptyList(), null)
        } else {
            Window(history.drop(startIdx), history.take(startIdx), history[startIdx].id)
        }

    /**
     * Aviso que se añade al system cuando parte del historial no viaja (recorte por
     * ventana o compactación manual). Con resumen rodante, el resumen; sin él, se ancla
     * la petición original del usuario si quedó fuera de la ventana.
     *
     * Va en el system y no en el prefijo efímero porque solo cambia cuando cambia el
     * corte (o llega un resumen nuevo), y en ese momento el cache ya se invalida de
     * todas formas por el inicio de la ventana.
     */
    fun truncationNotice(
        history: List<ChatMessage>,
        windowed: List<ChatMessage>,
        truncated: Boolean,
        contextSummary: String?
    ): String? {
        if (!truncated) return null
        if (!contextSummary.isNullOrBlank()) return "Resumen del historial anterior:\n$contextSummary"
        val firstUserTask = history.firstOrNull { it.role == Role.User }
            ?.takeIf { task -> windowed.none { it.id == task.id } }
            ?.content?.trim()?.take(500)
        return buildString {
            append("Nota: El historial anterior fue recortado por límite de contexto.")
            if (!firstUserTask.isNullOrBlank()) {
                append(" La petición original del usuario fue: \"")
                append(firstUserTask)
                append("\"")
            }
        }
    }

    /**
     * Monta la lista final de mensajes.
     *
     * - [systemContent] va como ÚNICO `role=system` en la posición 0: algunas plantillas
     *   Jinja (LM Studio/llama.cpp) rechazan cualquier otra disposición.
     * - [turnContext] (volátil) se antepone al último mensaje `user` de la ventana. Es una
     *   copia efímera: no se persiste y en el turno siguiente ese mensaje vuelve a viajar
     *   sin él, así que el cache se reprocesa desde el `user` anterior, no desde el
     *   principio. Si la ventana no contiene ningún `user` (turno de agente que ya ocupa
     *   todo el presupuesto), se pliega al system como último recurso.
     * - [nudge] va al final: pegado al último mensaje si es `user`, o como un `user`
     *   sintético tras el último assistant. Así sigue siendo "lo último que lee el modelo
     *   antes de generar" sin tocar nada de lo anterior.
     */
    fun assemble(
        systemContent: String,
        windowed: List<ChatMessage>,
        systemPromptId: String,
        turnContext: String? = null,
        nudge: String? = null
    ): List<ChatMessage> {
        val ctx = turnContext?.trim()?.takeIf { it.isNotEmpty() }
        val nudgeText = nudge?.trim()?.takeIf { it.isNotEmpty() }?.let(::wrapNudge)

        val messages = windowed.toMutableList()
        var system = systemContent.trim()

        if (ctx != null) {
            val lastUserIdx = messages.indexOfLast { it.role == Role.User }
            if (lastUserIdx >= 0) {
                val msg = messages[lastUserIdx]
                messages[lastUserIdx] = msg.copy(content = ctx + "\n\n" + msg.content)
            } else {
                system = listOf(system, ctx).filter { it.isNotEmpty() }.joinToString("\n\n")
            }
        }

        if (nudgeText != null) {
            val last = messages.lastOrNull()
            if (last != null && last.role == Role.User) {
                messages[messages.lastIndex] = last.copy(content = last.content + "\n\n" + nudgeText)
            } else {
                messages += ChatMessage(
                    id = NUDGE_MESSAGE_ID,
                    role = Role.User,
                    content = nudgeText,
                    timestampEpochMs = 0L
                )
            }
        }

        if (system.isEmpty()) return messages
        return listOf(
            ChatMessage(id = systemPromptId, role = Role.System, content = system, timestampEpochMs = 0L)
        ) + messages
    }

    /**
     * Marca el nudge como instrucción del sistema: viaja con `role=user` (el único sitio
     * al final que aceptan todas las plantillas), y sin la marca el modelo lo tomaría por
     * algo que escribió el usuario.
     */
    private fun wrapNudge(text: String): String =
        "<recordatorio-del-sistema>\n$text\n</recordatorio-del-sistema>"
}
