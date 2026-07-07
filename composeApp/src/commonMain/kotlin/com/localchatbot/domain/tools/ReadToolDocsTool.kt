package com.localchatbot.domain.tools

import com.localchatbot.core.storage.ToolDocsStore
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool de consulta lazy de `tools.md`: el modelo la llama cuando no sabe cómo usar
 * una herramienta o una tool le falla repetidamente. Devuelve la guía completa
 * (curada por nosotros) en vez de inflar el system prompt en cada request.
 *
 * Read-only (sin confirmación). Solo desktop — el archivo vive en `~/.localchatbot/`.
 */
class ReadToolDocsTool(
    private val store: ToolDocsStore,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val activityLabel: String = "Consultando guía de tools…"

    override suspend fun isAvailable(): Boolean = store.isAvailable

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Returns the tool guide (tools.md): non-obvious behaviour, alternate " +
                "modes, and recovery steps for the available tools. Call this when you're unsure " +
                "how to use a tool, or when a tool keeps failing (e.g. an edit_file match fails " +
                "twice) — read the relevant section, then retry correctly. Takes no arguments.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val docs = store.read()
            ?: return errorPayload("La guía de tools no está disponible en esta plataforma.")
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", true)
                put("docs", docs)
            }
        )
    }

    private fun errorPayload(message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", false)
                put("error", message)
            }
        )

    companion object {
        const val TOOL_NAME = "read_tool_docs"
    }
}
