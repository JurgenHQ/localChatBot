package com.localchatbot.domain.model

/**
 * Un modelo disponible en el servidor, con su estado de carga cuando el backend
 * lo expone (LM Studio). Para backends OpenAI planos `loaded` es null: solo
 * sabemos que el modelo existe, no si está en memoria.
 */
data class AvailableModel(
    /** Identificador que se envía en las requests de chat y se persiste en prefs. */
    val id: String,
    val displayName: String? = null,
    /** true/false según LM Studio; null = estado desconocido (backend OpenAI plano). */
    val loaded: Boolean? = null,
    /** Instancias cargadas de este modelo (ids para unload). Vacío si no está cargado. */
    val instanceIds: List<String> = emptyList(),
    /** Tamaño legible, p. ej. "7B". */
    val paramsString: String? = null,
    val maxContextLength: Int? = null
)

/**
 * Resultado de listar modelos con detalle. `canManage` indica si el servidor
 * soporta cargar/descargar modelos desde la app (API v1 de LM Studio >= 0.4.0).
 */
data class ModelCatalog(
    val models: List<AvailableModel>,
    val canManage: Boolean
)
