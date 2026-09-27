package com.localchatbot.core.storage

import com.localchatbot.domain.model.SkillDefinition

data class FolderImportResult(
    val imported: List<SkillDefinition>,
    val skipped: Int,
    val errors: List<String>
)

expect class SkillFileStore {
    val isAvailable: Boolean
    fun loadAll(): List<SkillDefinition>
    fun saveAll(skills: List<SkillDefinition>)
    fun importFromFolder(folderPath: String): FolderImportResult
    /** Returns the absolute path to the skill's directory, or null if not available. */
    fun skillDirPath(skillId: String): String?
}

expect fun createSkillFileStore(): SkillFileStore
