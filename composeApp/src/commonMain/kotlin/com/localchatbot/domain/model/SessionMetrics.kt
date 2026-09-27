package com.localchatbot.domain.model

/** Consumo atribuido a un modelo concreto dentro de una sesión. */
data class ModelUsage(
    val model: String,
    val turns: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    /** Promedio ponderado por duración de generación. Null si ningún turno trae tiempos. */
    val tokensPerSecond: Double?,
    /** Null si [model] no está en [ModelPricing] (lo normal en modelos locales). */
    val estimatedCostUsd: Double?
)

/**
 * Métricas agregadas de una sesión completa (roadmap 4.3).
 *
 * El desglose por modelo es real desde la migración `1.sqm`: cada mensaje recuerda con qué
 * modelo se generó ([ChatMessage.model]), así que cambiar de modelo a mitad de conversación
 * ya no reatribuye los turnos anteriores. Los mensajes anteriores a esa migración tienen
 * `model = null` y caen al modelo de la sesión, que es exactamente lo que se mostraba antes.
 */
data class SessionMetrics(
    /** Ordenado por tokens totales descendente. */
    val perModel: List<ModelUsage>,
    val turnsWithMetrics: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val avgTokensPerSecond: Double?,
    /**
     * Suma de los costes conocidos. Null si **ningún** modelo de la sesión tiene precio.
     * Si solo algunos lo tienen, es un total parcial: [modelsWithoutPrice] dice cuáles faltan.
     */
    val estimatedCostUsd: Double?,
    val modelsWithoutPrice: List<String>,
    /** Nombre de tool → veces ejecutada, ya ordenado de mayor a menor. */
    val toolUsageCounts: List<Pair<String, Int>>
)

/**
 * [sessionModel] es el modelo actual de la sesión, usado como respaldo para los mensajes
 * cuyo `model` es null (los anteriores a la migración `1.sqm`).
 */
fun aggregateSessionMetrics(messages: List<ChatMessage>, sessionModel: String): SessionMetrics {
    // Solo los mensajes con métricas: son los que representan un turno del modelo.
    val timedByModel = messages
        .filter { it.metrics != null }
        .groupBy { it.model ?: sessionModel }

    val perModel = timedByModel.map { (model, msgs) ->
        val metrics = msgs.mapNotNull { it.metrics }
        val input = metrics.sumOf { it.inputTokens ?: 0 }
        val output = metrics.sumOf { it.outputTokens ?: 0 }

        // Pondera por cuánto duró realmente cada turno, en vez de promediar los tokens/s de
        // cada uno por igual (que sobre-pesaría un turno corto frente a uno largo).
        val timed = metrics.filter { it.outputTokens != null && (it.generationMs ?: 0) > 0 }
        val sumMs = timed.sumOf { it.generationMs!! }
        val tps = if (timed.isEmpty() || sumMs <= 0) null else timed.sumOf { it.outputTokens!! } / (sumMs / 1000.0)

        ModelUsage(
            model = model,
            turns = metrics.size,
            inputTokens = input,
            outputTokens = output,
            tokensPerSecond = tps,
            estimatedCostUsd = ModelPricing.find(model)?.let { price ->
                (input / 1_000_000.0) * price.inputPerMillion + (output / 1_000_000.0) * price.outputPerMillion
            }
        )
    }.sortedByDescending { it.inputTokens + it.outputTokens }

    val knownCosts = perModel.mapNotNull { it.estimatedCostUsd }

    val allMetrics = messages.mapNotNull { it.metrics }
    val allTimed = allMetrics.filter { it.outputTokens != null && (it.generationMs ?: 0) > 0 }
    val allMs = allTimed.sumOf { it.generationMs!! }

    return SessionMetrics(
        perModel = perModel,
        turnsWithMetrics = allMetrics.size,
        totalInputTokens = allMetrics.sumOf { it.inputTokens ?: 0 },
        totalOutputTokens = allMetrics.sumOf { it.outputTokens ?: 0 },
        avgTokensPerSecond = if (allTimed.isEmpty() || allMs <= 0) null
        else allTimed.sumOf { it.outputTokens!! } / (allMs / 1000.0),
        estimatedCostUsd = if (knownCosts.isEmpty()) null else knownCosts.sum(),
        modelsWithoutPrice = perModel.filter { it.estimatedCostUsd == null }.map { it.model },
        toolUsageCounts = messages.mapNotNull { it.toolName }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
    )
}
