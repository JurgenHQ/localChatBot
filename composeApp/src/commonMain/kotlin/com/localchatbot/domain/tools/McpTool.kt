package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.data.mcp.McpCallToolResult
import com.localchatbot.data.mcp.McpClient
import com.localchatbot.data.mcp.McpContent
import com.localchatbot.data.mcp.McpToolInfo
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
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
            .map { it.toText() }
            .getOrElse { "MCP error: ${it.message}" }
            .let { truncateToolOutput(it) }
    }

    private fun McpCallToolResult.toText(): String {
        if (content.isEmpty()) return "(empty response)"
        return content.joinToString("\n") { item ->
            when (item) {
                is McpContent.Text -> item.text
                is McpContent.Image -> "[image: ${item.mimeType}]"
            }
        }.also { if (isError) return "MCP tool error: $it" }
    }

    companion object {
        fun sanitize(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }
}
