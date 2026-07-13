package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.data.mcp.McpCallToolResult
import com.localchatbot.data.mcp.McpClient
import com.localchatbot.data.mcp.McpContent
import com.localchatbot.data.mcp.McpToolInfo
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class McpTool(
    private val serverId: String,
    private val toolInfo: McpToolInfo,
    private val client: McpClient,
    private val confirm: ToolConfirmationController,
    private val json: Json
) : Tool {

    override val name: String = sanitize("mcp_${serverId}_${toolInfo.name}")

    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "MCP: ${toolInfo.name}…"

    /**
     * Última imagen (data URL) recibida en un resultado MCP — típicamente una captura
     * de `browser_take_screenshot`. Se drena en el use case y se adjunta al mensaje del
     * chat (out-of-band, igual que `generate_image`): así el usuario la ve sin que el
     * base64 viaje al modelo.
     */
    private val _lastImage = MutableStateFlow<String?>(null)

    override fun peekProducedImage(): String? = _lastImage.value

    override fun consumeProducedImage(): String? {
        val v = _lastImage.value
        _lastImage.value = null
        return v
    }

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = name,
            description = toolInfo.description ?: toolInfo.name,
            parameters = toolInfo.inputSchema
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val approved = confirm.requestApproval(
            title = "MCP: ${toolInfo.name}",
            detail = argumentsJson.take(120)
        )
        if (!approved) return "Tool call rejected by user."

        val args = runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject
        }.getOrNull()

        return client.callTool(toolInfo.name, args)
            .map { result ->
                // Captura la primera imagen del resultado (p. ej. captura de pantalla)
                // para mostrarla en el chat; el texto que ve el modelo no la incluye.
                result.content.filterIsInstance<McpContent.Image>().firstOrNull()?.let { img ->
                    _lastImage.value = "data:${img.mimeType};base64,${img.data}"
                }
                result.toText()
            }
            .getOrElse { "MCP error: ${it.message}" }
            .let { truncateToolOutput(it) }
    }

    private fun McpCallToolResult.toText(): String {
        if (content.isEmpty()) return "(empty response)"
        return content.joinToString("\n") { item ->
            when (item) {
                is McpContent.Text -> item.text
                is McpContent.Image -> "[imagen ${item.mimeType} recibida — mostrada en el chat]"
            }
        }.also { if (isError) return "MCP tool error: $it" }
    }

    companion object {
        fun sanitize(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }
}
