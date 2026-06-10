package com.localchatbot.domain.tools

import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.InstalledSkill
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.skill.SkillCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class UseSkillTool(
    private val installedSkillsProvider: suspend () -> List<InstalledSkill>,
    private val skillLookup: suspend (String) -> SkillDefinition? = { SkillCatalog.byId(it) }
) : Tool {

    override val name = "use_skill"

    override val activityLabel = "Cargando skill…"

    override val definition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "use_skill",
            description = "Load the full instructions for an installed skill. Call this when the user's request matches the description of a skill listed in the system prompt skills index.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("skill_id", buildJsonObject {
                        put("type", "string")
                        put("description", "The ID of the skill to load (from the skills index in the system prompt).")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("skill_id")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun isAvailable(): Boolean =
        installedSkillsProvider().any { it.enabled }

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject
        }.getOrNull() ?: return """{"error":"JSON de argumentos inválido"}"""

        val skillId = args["skill_id"]?.jsonPrimitive?.content
            ?: return """{"error":"skill_id requerido"}"""

        val installed = installedSkillsProvider()
        val entry = installed.firstOrNull { it.skillId == skillId }
            ?: return """{"error":"Skill '$skillId' no está instalado"}"""

        if (!entry.enabled) return """{"error":"Skill '$skillId' está deshabilitado"}"""

        val skill = skillLookup(skillId)
            ?: return """{"error":"Skill '$skillId' no encontrado en el catálogo"}"""

        return buildJsonObject {
            put("skill_id", skillId)
            put("instructions", skill.systemPromptAddition)
        }.toString()
    }
}
