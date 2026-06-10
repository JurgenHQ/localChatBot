package com.localchatbot.core.storage

import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.skill.SkillMarkdown
import java.io.File

actual class SkillFileStore {
    private val skillsDir = File(System.getProperty("user.home"), ".localchatbot/skills")

    // id → backing file path (populated by loadAll, updated by saveAll)
    private val idToFile = mutableMapOf<String, File>()

    actual val isAvailable: Boolean = true

    actual fun loadAll(): List<SkillDefinition> {
        skillsDir.mkdirs()
        idToFile.clear()
        val result = mutableListOf<SkillDefinition>()
        val seenIds = mutableSetOf<String>()

        skillsDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            var skill = SkillMarkdown.parse(text, fallbackName = file.nameWithoutExtension)
                ?: return@forEach

            // Repair: blank or duplicate id → generate fresh from name, rewrite file
            if (skill.id.isBlank() || skill.id in seenIds) {
                val repairedId = freshId(skill.name, seenIds)
                skill = skill.copy(id = repairedId)
                runCatching { file.writeText(SkillMarkdown.serialize(skill)) }
            }

            idToFile[skill.id] = file
            seenIds.add(skill.id)
            result += skill
        }
        return result
    }

    actual fun saveAll(skills: List<SkillDefinition>) {
        skillsDir.mkdirs()
        val keptIds = skills.map { it.id }.toSet()

        // Delete orphaned files not in idToFile (e.g. from prior blank-id imports)
        skillsDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
            if (idToFile.values.contains(file)) return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val parsedId = SkillMarkdown.parse(text, fallbackName = "")?.id ?: ""
            if (parsedId !in keptIds) file.delete()
        }

        // Delete tracked files for removed skills
        idToFile.entries.removeAll { (id, file) ->
            if (id !in keptIds) { file.delete(); true } else false
        }

        // Write/update each skill
        for (skill in skills) {
            val file = idToFile[skill.id] ?: run {
                val slug = SkillMarkdown.slugify(skill.name)
                uniqueFile(slug).also { idToFile[skill.id] = it }
            }
            runCatching { file.writeText(SkillMarkdown.serialize(skill)) }
        }
    }

    actual fun importFromFolder(folderPath: String): FolderImportResult {
        val folder = File(folderPath)
        if (!folder.isDirectory) return FolderImportResult(emptyList(), 0, listOf("No es una carpeta válida"))

        skillsDir.mkdirs()
        val imported = mutableListOf<SkillDefinition>()
        val errors = mutableListOf<String>()
        var skipped = 0

        // (a) Subdirectories containing SKILL.md (Claude Code format)
        folder.listFiles { f -> f.isDirectory }?.forEach { subDir ->
            val skillFile = File(subDir, "SKILL.md")
            if (!skillFile.exists()) { skipped++; return@forEach }
            processImportFile(skillFile, fallbackName = subDir.name, imported, errors)
        }

        // (b) Loose *.md files in folder root
        folder.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { file ->
            processImportFile(file, fallbackName = file.nameWithoutExtension, imported, errors)
        }

        return FolderImportResult(imported, skipped, errors)
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun processImportFile(
        file: File,
        fallbackName: String,
        imported: MutableList<SkillDefinition>,
        errors: MutableList<String>
    ) {
        val text = runCatching { file.readText() }.getOrElse {
            errors += "No se pudo leer ${file.name}: ${it.message}"
            return
        }
        val parsed = SkillMarkdown.parse(text, fallbackName = fallbackName)
        if (parsed == null) {
            errors += "Markdown inválido: ${file.name}"
            return
        }

        val slug = SkillMarkdown.slugify(parsed.name)
        val baseId = "custom_$slug"

        // Dedup: same name → reuse existing id+file (update, not duplicate)
        val (finalId, destFile) = if (baseId in idToFile) {
            baseId to idToFile[baseId]!!
        } else {
            val newId = freshId(parsed.name, idToFile.keys.toSet())
            newId to uniqueFile(slug)
        }

        val skill = parsed.copy(id = finalId)
        runCatching { destFile.writeText(SkillMarkdown.serialize(skill)) }.onFailure {
            errors += "No se pudo copiar ${file.name}: ${it.message}"
            return
        }
        idToFile[finalId] = destFile
        imported += skill
    }

    private fun freshId(name: String, existingIds: Set<String>): String {
        val base = "custom_${SkillMarkdown.slugify(name)}"
        if (base !in existingIds) return base
        var i = 2
        while ("$base-$i" in existingIds) i++
        return "$base-$i"
    }

    private fun uniqueFile(slug: String): File {
        var candidate = File(skillsDir, "$slug.md")
        var suffix = 2
        while (candidate.exists() || idToFile.values.contains(candidate)) {
            candidate = File(skillsDir, "$slug-$suffix.md")
            suffix++
        }
        return candidate
    }
}

actual fun createSkillFileStore(): SkillFileStore = SkillFileStore()
