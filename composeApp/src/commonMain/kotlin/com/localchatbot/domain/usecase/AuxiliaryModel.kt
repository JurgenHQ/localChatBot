package com.localchatbot.domain.usecase

import com.localchatbot.domain.model.ConnectionConfig

/**
 * Corre una tarea auxiliar (título, resumen) con el perfil auxiliar y, si no hay o no
 * devuelve nada, con el principal. El fallback importa: el modelo auxiliar suele ser un
 * segundo servidor o un modelo que puede no estar cargado, y un `/compact` que falla por eso
 * es peor que uno que tarda un poco más en el modelo grande.
 */
suspend fun <T : Any> withAuxiliaryModel(
    auxiliary: ConnectionConfig?,
    main: ConnectionConfig,
    task: suspend (ConnectionConfig) -> T?
): T? = auxiliary?.takeIf { it != main }?.let { runCatching { task(it) }.getOrNull() } ?: task(main)

/** API key a mandar para [config]: la suya, o null (sin header) si está vacía. */
fun ConnectionConfig.apiKeyOrNull(): String? = apiKey.takeIf { it.isNotBlank() }
