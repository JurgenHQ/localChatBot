package com.localchatbot.data.mcp

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.domain.model.McpTransportConfig
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.tools.McpTool
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class McpToolProvider(
    private val prefs: PreferencesRepository,
    private val httpClient: HttpClient,
    private val confirm: ToolConfirmationController,
    private val json: Json,
    private val inspector: NetworkInspector? = null
) {
    private data class ServerState(
        val client: McpClient,
        val tools: List<McpTool>,
        val isStdio: Boolean
    )

    private val mutex = Mutex()
    private val serverStates = mutableMapOf<String, ServerState>()

    suspend fun currentTools(): List<McpTool> = mutex.withLock {
        val servers = prefs.current().mcpServers.filter { it.enabled }
        val toRemove = serverStates.keys - servers.map { it.id }.toSet()
        toRemove.forEach { id ->
            runCatching { serverStates[id]?.client?.close() }
            serverStates.remove(id)
        }
        val tools = mutableListOf<McpTool>()
        for (server in servers) {
            val state = serverStates[server.id] ?: connectServer(server) ?: continue
            serverStates[server.id] = state
            tools += state.tools
        }
        tools
    }

    suspend fun testConnection(serverId: String): Result<Int> {
        val server = prefs.current().mcpServers.firstOrNull { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException("Server $serverId not found"))
        return runCatching {
            val state = connectServer(server) ?: error("Transport not available on this platform")
            mutex.withLock { serverStates[serverId] = state }
            state.tools.size
        }
    }

    suspend fun closeAll() = mutex.withLock {
        serverStates.values.forEach { runCatching { it.client.close() } }
        serverStates.clear()
    }

    private suspend fun connectServer(server: McpServerConfig): ServerState? {
        val (transport, isStdio) = when (val t = server.transport) {
            is McpTransportConfig.Http -> {
                val tr = HttpMcpTransport(t.url, t.headers, httpClient, json, inspector)
                tr to false
            }
            is McpTransportConfig.Stdio -> {
                val tr = createStdioTransport(t.command, t.args, t.env) ?: return null
                tr to true
            }
        }
        val client = McpClient(transport, json)
        return runCatching {
            client.initialize().getOrThrow()
            val toolInfos = client.listTools().getOrThrow()
            val mcpTools = toolInfos.take(MAX_TOOLS_PER_SERVER).map { info ->
                McpTool(
                    serverId = server.id,
                    toolInfo = info,
                    client = client,
                    confirm = confirm,
                    isStdio = isStdio,
                    json = json
                )
            }
            ServerState(client = client, tools = mcpTools, isStdio = isStdio)
        }.getOrElse {
            runCatching { client.close() }
            null
        }
    }

    companion object {
        private const val MAX_TOOLS_PER_SERVER = 30
    }
}
