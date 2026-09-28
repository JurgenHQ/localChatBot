package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GenerationParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Int? = null,
    /**
     * `min_p` y `repeat_penalty`: los muestreadores que de verdad se ajustan con modelos
     * locales. Los entienden llama.cpp, LM Studio y Ollama por el endpoint OpenAI; OpenAI
     * los rechaza, pero con null no se mandan.
     */
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    /**
     * `reasoning_effort` para modelos con modo thinking (DeepSeek v4, OpenAI o-series y
     * compatibles). Niveles según la API de DeepSeek: `low`/`high`/`max` (default del
     * servidor es `high`; `medium` existe solo como alias de compatibilidad de `high`, así
     * que no se ofrece como opción propia). Backends que no lo soportan lo ignoran como
     * cualquier campo desconocido del request — no rompe nada, simplemente no tiene efecto.
     * Cuando está seteado, [com.localchatbot.data.repository.ModelRepositoryImpl] omite
     * temperature/top_p/presence_penalty/frequency_penalty: DeepSeek documenta esos
     * parámetros como incompatibles con el modo thinking.
     */
    val reasoningEffort: String? = null
) {
    /**
     * Campo a campo, lo propio y si no lo de [fallback]. Así un perfil puede fijar solo la
     * temperatura y heredar el resto de los parámetros globales.
     */
    fun orElse(fallback: GenerationParams): GenerationParams = GenerationParams(
        temperature = temperature ?: fallback.temperature,
        topP = topP ?: fallback.topP,
        maxTokens = maxTokens ?: fallback.maxTokens,
        presencePenalty = presencePenalty ?: fallback.presencePenalty,
        frequencyPenalty = frequencyPenalty ?: fallback.frequencyPenalty,
        seed = seed ?: fallback.seed,
        minP = minP ?: fallback.minP,
        repeatPenalty = repeatPenalty ?: fallback.repeatPenalty,
        reasoningEffort = reasoningEffort ?: fallback.reasoningEffort
    )

    companion object {
        val REASONING_EFFORT_LEVELS = listOf("low", "high", "max")
    }
}
