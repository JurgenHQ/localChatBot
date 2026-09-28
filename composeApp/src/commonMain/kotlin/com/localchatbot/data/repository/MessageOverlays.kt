package com.localchatbot.data.repository

import com.localchatbot.domain.model.ChatMessage

/**
 * Texto de un mensaje que se está generando y todavía no bajó a SQLite (ver
 * [com.localchatbot.domain.repository.ChatRepository.setStreamingPreview]). Un campo null
 * significa "sin cambios": se muestra lo que haya en la BD.
 */
data class StreamingPreview(
    val sessionId: String,
    val content: String?,
    val reasoning: String?
)

/**
 * Funde el estado que vive solo en memoria sobre los mensajes leídos de la BD: la media
 * transitoria (imágenes/vídeos, nunca persistidos) y el texto en streaming aún sin volcar.
 *
 * Es pura y barata a propósito: el mapeo fila → dominio (que deserializa las columnas JSON)
 * se hace una vez por cambio de BD, y esto corre en cada tick del streaming solo haciendo
 * `copy()` de los mensajes afectados. Si no hay nada que aplicar devuelve la misma lista.
 */
fun applyOverlays(
    messages: List<ChatMessage>,
    media: Map<String, Pair<String?, String?>>,
    streaming: Map<String, StreamingPreview>
): List<ChatMessage> {
    if (media.isEmpty() && streaming.isEmpty()) return messages
    return messages.map { m ->
        val mediaEntry = media[m.id]
        val preview = streaming[m.id]
        if (mediaEntry == null && preview == null) return@map m
        m.copy(
            imageDataUrl = mediaEntry?.first ?: m.imageDataUrl,
            videoDataUrl = mediaEntry?.second ?: m.videoDataUrl,
            content = preview?.content ?: m.content,
            reasoning = preview?.reasoning ?: m.reasoning
        )
    }
}
