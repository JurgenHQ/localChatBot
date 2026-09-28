package com.localchatbot.domain.usecase

/**
 * Corrige la estimación de tokens (~4 caracteres por token) con lo que mide el servidor.
 *
 * El ratio fijo se queda corto justo en lo que más pesa en una sesión de agente: código,
 * JSON y español tokenizan a ~3 caracteres por token, y además la estimación no ve las
 * definiciones de tools que viajan en cada petición. Con el ratio fijo la ventana podía
 * pasarse del contexto real y el servidor rechazaba o truncaba la petición.
 *
 * Tras cada ronda con `usage` real se compara `prompt_tokens` con lo que se estimó para esa
 * misma petición, y el factor resultante multiplica las estimaciones siguientes. Es por
 * modelo porque el tokenizer es del modelo; vive en memoria y el primer turno tras
 * arrancar usa el ratio fijo, igual que antes.
 */
object TokenCalibration {
    /** Por debajo, la estimación sobreestimaría; se deja un margen en vez de exprimir el contexto. */
    const val MIN_FACTOR = 0.8

    /** Tope ante medidas absurdas (un servidor que suma tokens de imagen, un `usage` roto). */
    const val MAX_FACTOR = 2.5

    /** Peticiones más chicas que esto no calibran: el overhead fijo del formato las domina. */
    const val MIN_SAMPLE_TOKENS = 500

    /**
     * Nuevo factor tras medir [measuredTokens] en una petición que se estimó en
     * [estimatedTokens]. Se promedia con el anterior para que una medida rara no mueva la
     * ventana de golpe (y con ella el cache de llama.cpp). Null si la muestra no sirve.
     */
    fun update(previous: Double?, measuredTokens: Int, estimatedTokens: Int): Double? {
        if (measuredTokens <= 0 || estimatedTokens < MIN_SAMPLE_TOKENS) return previous
        val sample = (measuredTokens.toDouble() / estimatedTokens).coerceIn(MIN_FACTOR, MAX_FACTOR)
        return if (previous == null) sample else (previous + sample) / 2
    }

    /** Presupuesto de la ventana expresado en tokens *estimados*. */
    fun estimatedBudget(realBudgetTokens: Int, factor: Double?): Int =
        if (factor == null) realBudgetTokens else (realBudgetTokens / factor).toInt()
}
