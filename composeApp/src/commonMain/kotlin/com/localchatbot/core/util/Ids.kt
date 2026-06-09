package com.localchatbot.core.util

import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Genera IDs únicos para sesiones, mensajes, etc.: epoch-ms en base36 +
 * sufijo aleatorio. Ordenable cronológicamente y con colisiones improbables.
 * Única implementación — antes estaba duplicada en UseCases,
 * ChatRepositoryImpl y ModelRepositoryImpl.
 */
fun newId(): String =
    Clock.System.now().toEpochMilliseconds().toString(36) +
        "-" + Random.nextInt(0, 1_000_000).toString(36)
