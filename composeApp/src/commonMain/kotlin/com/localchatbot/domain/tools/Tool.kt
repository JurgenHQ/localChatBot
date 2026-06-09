package com.localchatbot.domain.tools

import com.localchatbot.data.remote.ToolDefinition

/**
 * Abstracción mínima para tools invocables por el modelo vía OpenAI function calling.
 *
 * - `definition` es lo que se envía al modelo (`tools` en `ChatCompletionRequest`).
 * - `execute` recibe el JSON `arguments` que produce el modelo y devuelve el resultado
 *   que se pasará como `role=tool` en el siguiente pase.
 * - `isAvailable` indica si la tool puede ejecutarse en este momento (p. ej. si tiene
 *   API key configurada). Las tools no disponibles igualmente se envían al modelo y
 *   ejecutan — devuelven un error explicativo — pero el use case puede saltarse
 *   indicadores de UI como "Buscando…".
 */
interface Tool {
    val name: String
    val definition: ToolDefinition
    suspend fun isAvailable(): Boolean = true
    suspend fun execute(argumentsJson: String): String

    /**
     * Etiqueta corta para mostrar en la UI mientras la tool corre.
     * Si null, no se muestra indicador específico (cae al typing indicator genérico).
     */
    val requiresConfirmation: Boolean get() = false

    val activityLabel: String? get() = null

    /**
     * Detalle contextual (la query de búsqueda, el prompt de imagen…) extraído de los
     * argumentos. Se muestra junto al [activityLabel] como subtítulo de la actividad.
     */
    fun activityDetail(argumentsJson: String): String? = null

    /**
     * Si la tool produjo una imagen "out of band" (no devuelta al modelo en el JSON
     * de tool result, sino almacenada aparte para no inflar el contexto), devuelve aquí
     * el data URL y consume el estado interno. Llamado por el use case tras cada ronda.
     */
    fun consumeProducedImage(): String? = null
}

private const val MAX_TOOL_OUTPUT_CHARS = 8_000
private const val TRUNCATION_HEAD = 3_000
private const val TRUNCATION_TAIL = 1_000

fun truncateToolOutput(result: String): String {
    if (result.length <= MAX_TOOL_OUTPUT_CHARS) return result
    val omitted = result.length - TRUNCATION_HEAD - TRUNCATION_TAIL
    return result.take(TRUNCATION_HEAD) +
        "\n[… $omitted chars truncados …]\n" +
        result.takeLast(TRUNCATION_TAIL)
}

class ToolRegistry(private val tools: List<Tool>) {

    fun allDefinitions(): List<ToolDefinition> = tools.map { it.definition }

    /**
     * Solo las definiciones de tools cuyo [Tool.isAvailable] devuelve true.
     * Si [allowedNames] no es null, además se filtra por nombre — usado por
     * perfiles de contexto (p. ej. modo coche: solo `search_web`).
     */
    suspend fun availableDefinitions(allowedNames: Set<String>? = null): List<ToolDefinition> =
        tools.filter { (allowedNames == null || it.name in allowedNames) && it.isAvailable() }
            .map { it.definition }

    fun allTools(): List<Tool> = tools

    fun find(name: String): Tool? = tools.firstOrNull { it.name == name }

    fun isEmpty(): Boolean = tools.isEmpty()
}
