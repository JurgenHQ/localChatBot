package com.localchatbot.core.storage

import com.localchatbot.domain.model.SkillDefinition

actual class SkillFileStore {
    actual val isAvailable: Boolean = false
    actual fun loadAll(): List<SkillDefinition> = emptyList()
    actual fun saveAll(skills: List<SkillDefinition>) = Unit
    actual fun importFromFolder(folderPath: String): FolderImportResult =
        FolderImportResult(emptyList(), 0, emptyList())
}

actual fun createSkillFileStore(): SkillFileStore = SkillFileStore()
