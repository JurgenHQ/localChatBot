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
}

expect fun createSkillFileStore(): SkillFileStore
