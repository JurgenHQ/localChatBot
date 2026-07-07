package com.localchatbot.domain.tools

import com.localchatbot.core.storage.MemoryStore
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lectura completa de `memory.md` (preferencias del usuario). El system prompt ya
 * inyecta un resumen; esta tool devuelve el detalle íntegro cuando el modelo lo
 * necesita (p. ej. antes de un commit, para aplicar una convención concreta).
 *
 * Read-only, sin confirmación. Solo desktop.
 */
class ReadMemoryTool(
    private val store: MemoryStore,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val activityLabel: String = "Consultando memoria…"

    override suspend fun isAvailable(): Boolean = store.isAvailable

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Returns the user's full saved memory (memory.md): their stated " +
                "preferences for commits, naming, tone, language, tooling, etc. A short summary " +
                "is already in the system prompt; call this for the complete list before tasks " +
                "where the user's conventions matter. Takes no arguments.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val content = store.read()
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", true)
                put("memory", content ?: "(memoria vacía — aún no hay preferencias guardadas)")
            }
        )
    }

    companion object {
        const val TOOL_NAME = "read_memory"
    }
}
