package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ScriptParam(
    val name: String,
    val description: String,
    val required: Boolean = true
)

@Serializable
data class SkillScript(
    val name: String,
    val description: String,
    val command: String,
    val params: List<ScriptParam> = emptyList()
)

@Serializable
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val fullDescription: String,
    val systemPromptAddition: String,
    val scripts: List<SkillScript> = emptyList()
)

@Serializable
data class InstalledSkill(val skillId: String, val enabled: Boolean = true)

@Serializable
data class SkillsExport(
    val version: Int = 1,
    val skills: List<SkillDefinition>
)
