package com.localchatbot.domain.model

/** Precio USD por millón de tokens de un modelo cloud conocido. */
data class ModelPrice(val inputPerMillion: Double, val outputPerMillion: Double)

/**
 * Precios de modelos cloud conocidos, para estimar el coste acumulado de una sesión en
 * el panel de métricas (roadmap 4.3). Tabla estática y deliberadamente aproximada: no hay
 * ningún concepto de "proveedor" o precio en [ConnectionConfig]/[ConnectionProfile], y
 * mantenerla sincronizada con los cambios de precio de cada proveedor está fuera de
 * alcance. Los modelos locales (LM Studio, llama.cpp, Ollama) no matchean nada acá, así
 * que simplemente no se les inventa un coste.
 *
 * El match es por substring case-insensitive sobre el nombre de modelo configurado,
 * porque los proveedores devuelven ids con sufijos de fecha/versión (p.ej.
 * "claude-3-5-sonnet-20241022", "gpt-4o-2024-08-06"). El orden de la tabla importa: las
 * variantes más específicas ("gpt-4o-mini") van antes que su prefijo ("gpt-4o") para que
 * no las capture la entrada equivocada.
 */
object ModelPricing {
    private val table: List<Pair<String, ModelPrice>> = listOf(
        "gpt-4o-mini" to ModelPrice(0.15, 0.60),
        "gpt-4o" to ModelPrice(2.50, 10.00),
        "gpt-4.1-mini" to ModelPrice(0.40, 1.60),
        "gpt-4.1-nano" to ModelPrice(0.10, 0.40),
        "gpt-4.1" to ModelPrice(2.00, 8.00),
        "o1-mini" to ModelPrice(1.10, 4.40),
        "o1" to ModelPrice(15.00, 60.00),
        "gpt-3.5-turbo" to ModelPrice(0.50, 1.50),
        "claude-3-5-haiku" to ModelPrice(0.80, 4.00),
        "claude-3-5-sonnet" to ModelPrice(3.00, 15.00),
        "claude-3-opus" to ModelPrice(15.00, 75.00),
        "gemini-1.5-flash" to ModelPrice(0.075, 0.30),
        "gemini-1.5-pro" to ModelPrice(1.25, 5.00),
        "deepseek-chat" to ModelPrice(0.27, 1.10)
    )

    /** Null si [model] no matchea ningún proveedor conocido (típicamente un modelo local). */
    fun find(model: String): ModelPrice? {
        val lower = model.lowercase()
        return table.firstOrNull { (key, _) -> lower.contains(key) }?.second
    }
}
