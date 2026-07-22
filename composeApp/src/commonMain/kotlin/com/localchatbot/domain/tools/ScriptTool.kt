package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.SkillScript
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ScriptTool(
    private val script: SkillScript,
    private val skillId: String,
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json,
    private val skillDir: String? = null
) : Tool {

    override val name: String = sanitize("sk_${skillId}_${script.name}")
    override val requiresConfirmation = true
    override val activityLabel = "Ejecutando ${script.name}…"
    override fun activityDetail(argumentsJson: String) = script.command.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = name,
            description = script.description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    script.params.forEach { param ->
                        put(param.name, buildJsonObject {
                            put("type", "string")
                            put("description", param.description.ifBlank { param.name })
                        })
                    }
                })
                val required = script.params.filter { it.required }.map { it.name }
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                }
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject
        }.getOrNull() ?: emptyMap<String, kotlinx.serialization.json.JsonElement>()

        var command = script.command
        args.forEach { (key, value) ->
            val str = runCatching { value.jsonPrimitive.content }.getOrDefault(value.toString())
            command = command.replace("{{$key}}", str)
        }

        // Resolve relative paths (e.g. "scripts/start-server.sh") against the skill dir
        if (skillDir != null && !command.startsWith("/")) {
            val parts = command.split(" ", limit = 2)
            val executable = parts[0]
            // Check if the first token is a relative path to a file in the skill dir
            if (executable.contains("/")) {
                command = "$skillDir/$executable" + if (parts.size > 1) " ${parts[1]}" else ""
            }
        }

        val workspaceDir = FsToolUtil.effectiveWorkspace(preferences)
            ?: return FsToolUtil.errorPayload(json, "Sin workspace configurado para ejecutar scripts")

        val approved = confirm.requestApproval(
            title = "Ejecutar script: ${script.name}",
            detail = command,
            force = false
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        return FsToolUtil.fsResultToJson(
            json,
            agent.runCommand(
                command = command,
                workingDir = workspaceDir,
                timeoutSeconds = 30,
                background = false
            )
        )
    }

    companion object {
        fun sanitize(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }
}
