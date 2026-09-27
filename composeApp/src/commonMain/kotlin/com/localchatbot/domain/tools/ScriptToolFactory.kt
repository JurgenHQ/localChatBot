package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.storage.SkillFileStore
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.skill.SkillCatalog
import kotlinx.serialization.json.Json

class ScriptToolFactory(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json,
    private val skillFileStore: SkillFileStore
) {
    suspend fun buildEnabledTools(): List<ScriptTool> {
        if (!FsToolUtil.isAvailable(preferences)) return emptyList()
        val prefs = preferences.current()
        val allSkills = SkillCatalog.allFor(prefs.customSkills)
        return prefs.installedSkills
            .filter { it.enabled }
            .mapNotNull { installed -> allSkills.firstOrNull { it.id == installed.skillId } }
            .flatMap { skill ->
                val dir = skillFileStore.skillDirPath(skill.id)
                skill.scripts.map { script ->
                    ScriptTool(script, skill.id, agent, confirm, preferences, json, skillDir = dir)
                }
            }
    }
}
