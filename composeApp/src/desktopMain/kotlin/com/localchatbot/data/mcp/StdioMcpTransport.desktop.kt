package com.localchatbot.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

actual fun createStdioTransport(
    command: String,
    args: List<String>,
    env: Map<String, String>
): McpTransportLayer? = StdioMcpTransportImpl(command, args, env)

private class StdioMcpTransportImpl(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>
) : McpTransportLayer {

    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private fun ensureProcess() {
        if (process?.isAlive == true) return
        val pb = ProcessBuilder(listOf(command) + args).apply {
            environment().putAll(env)
            redirectErrorStream(false)
        }
        val proc = pb.start()
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
    }

    override suspend fun sendRequest(method: String, params: JsonObject?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureProcess()
                val id = JsonPrimitive(nextId++)
                val request = JsonRpcRequest(id = id, method = method, params = params)
                val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)

                val w = writer ?: error("Process not started")
                w.write(requestJson)
                w.newLine()
                w.flush()

                val r = reader ?: error("Process not started")
                r.readLine() ?: error("Process closed stdout")
            }
        }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching {
            writer?.close()
            reader?.close()
            process?.destroy()
        }
        process = null
        writer = null
        reader = null
    }
}
