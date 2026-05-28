package com.localchatbot.data.remote

import kotlinx.serialization.Serializable

/**
 * DTOs del Diagram Service (FastAPI wrapper sobre mermaid-cli).
 * Endpoint: `POST <baseUrl>/generate-diagram`.
 */

@Serializable
data class DiagramRenderRequest(
    /** Código Mermaid (graph, mindmap, sequenceDiagram, classDiagram, etc.). */
    val code: String,
    /** Tema opcional: "default", "dark", "forest", "neutral". */
    val theme: String? = null,
    /** Color de fondo opcional (ej. "transparent", "#ffffff"). */
    val background: String? = null,
    /**
     * Ancho del canvas en píxeles antes de aplicar la escala.
     * Valores razonables: 1600–3200. Default del servidor si null.
     */
    val width: Int? = null,
    /**
     * Factor de escala del PNG de salida (equivale a --scale de mmdc).
     * 3 = imagen 3× más densa, perfecta para pantallas de alta densidad.
     * Default del servidor si null.
     */
    val scale: Int? = null
)

@Serializable
data class DiagramRenderResponse(
    val success: Boolean,
    val image_base64: String? = null,
    val filename: String? = null,
    val error: String? = null
)
