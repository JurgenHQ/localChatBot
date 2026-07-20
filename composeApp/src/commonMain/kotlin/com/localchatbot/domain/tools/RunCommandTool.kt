package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RunCommandTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Ejecutando comando…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["command"]?.jsonPrimitive?.content
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Runs a shell command (macOS/Linux: user's login shell; Windows: cmd). " +
                "Supports pipes, redirects, env vars, chained commands (`;` / `&&`). " +
                "Captures stdout, stderr, exitCode. Output truncated at ~50 KB each. " +
                "Default timeout 30 s, max 600 s. " +
                "IMPORTANT: quote any path containing spaces (the workspace path may have one) — " +
                "an unquoted path with spaces splits into multiple tokens and the command fails. " +
                "Prefer the `working_dir` parameter over `cd \"<path>\"` inside `command`. " +
                "For long-running processes (dev servers, watchers, etc.) set `background=true`: " +
                "the process starts in background, the tool waits up to `startup_check_seconds` " +
                "(default 5) to capture initial output (port, errors), then returns with " +
                "`background=true` and the process PID. Stop it later with `kill <pid>`.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run.")
                    })
                    put("working_dir", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional working directory. Defaults to the workspace.")
                    })
                    put("timeout_seconds", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in seconds for foreground commands. Default 30, max 600.")
                    })
                    put("background", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, start the process in background and return immediately with its PID. Use for dev servers, watchers, or any long-running process.")
                    })
                    put("startup_check_seconds", buildJsonObject {
                        put("type", "integer")
                        put("description", "Seconds to wait for initial output before returning in background mode. Default 5.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("command")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val command = args["command"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'command' faltante")

        val current = preferences.current()
        val workingDirInput = args["working_dir"]?.jsonPrimitive?.content
        val workspace = current.fsWorkspaceDir
            ?: return FsToolUtil.errorPayload(json, "Sin workspace configurado")

        val resolvedDir = if (workingDirInput.isNullOrBlank()) {
            workspace
        } else {
            FsToolUtil.resolvePath(agent, preferences, json, workingDirInput).getOrElse { e ->
                return FsToolUtil.errorPayload(json, e.message ?: "working_dir inválido")
            }
        }

        val background = args["background"]?.jsonPrimitive?.booleanOrNull == true
        val timeout = args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30
        val startupCheck = args["startup_check_seconds"]?.jsonPrimitive?.intOrNull ?: 5

        // Comandos que matchean la denylist fuerzan el diálogo de confirmación
        // incluso en YOLO mode — única defensa contra un modelo que alucina
        // un comando destructivo cuando las confirmaciones están apagadas.
        val dangerReason = DangerousCommands.match(command)

        val detail = buildString {
            append("$ ").append(command)
            append("\n\ncwd: ").append(resolvedDir)
            if (background) append("\nmodo: background (startup check ${startupCheck}s)")
            else append("\ntimeout: ${timeout}s")
            if (dangerReason != null) {
                append("\n\n⚠ PELIGROSO: ").append(dangerReason)
                append("\nSe pide confirmación aunque YOLO esté activo.")
            }
        }

        val approved = confirm.requestApproval(
            title = if (dangerReason != null) "⚠ Comando potencialmente destructivo" else "Ejecutar comando shell",
            detail = detail,
            force = dangerReason != null
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(
            json,
            agent.runCommand(command, resolvedDir, timeout, background, startupCheck)
        )
    }

    companion object {
        const val TOOL_NAME = "run_command"
    }
}
