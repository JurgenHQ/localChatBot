package com.localchatbot.core.storage

import com.localchatbot.domain.model.ScriptParam
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.model.SkillScript
import com.localchatbot.domain.skill.SkillMarkdown
import java.io.File

actual class SkillFileStore {
    private val skillsDir = File(System.getProperty("user.home"), ".localchatbot/skills")

    // id → backing directory (populated by loadAll, updated by saveAll)
    private val idToDir = mutableMapOf<String, File>()

    actual val isAvailable: Boolean = true

    actual fun loadAll(): List<SkillDefinition> {
        skillsDir.mkdirs()
        idToDir.clear()
        val result = mutableListOf<SkillDefinition>()
        val seenIds = mutableSetOf<String>()

        // New format: subdirectories with SKILL.md
        skillsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.exists()) return@forEach
            val text = runCatching { skillFile.readText() }.getOrNull() ?: return@forEach
            var skill = SkillMarkdown.parse(text, fallbackName = dir.name) ?: return@forEach

            // Repair: blank or duplicate id → generate fresh from name, rewrite file
            if (skill.id.isBlank() || skill.id in seenIds) {
                val repairedId = freshId(skill.name, seenIds)
                skill = skill.copy(id = repairedId)
                runCatching { skillFile.writeText(SkillMarkdown.serialize(skill)) }
            }

            idToDir[skill.id] = dir
            seenIds.add(skill.id)
            result += skill
        }

        // Legacy format: loose .md files (migrate on read)
        skillsDir.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            var skill = SkillMarkdown.parse(text, fallbackName = file.nameWithoutExtension)
                ?: return@forEach

            if (skill.id.isBlank() || skill.id in seenIds) {
                val repairedId = freshId(skill.name, seenIds)
                skill = skill.copy(id = repairedId)
            }

            // Migrate: move to subdirectory
            val slug = SkillMarkdown.slugify(skill.name)
            val dir = uniqueDir(slug)
            dir.mkdirs()
            val newFile = File(dir, "SKILL.md")
            runCatching {
                newFile.writeText(SkillMarkdown.serialize(skill))
                file.delete()
            }

            idToDir[skill.id] = dir
            seenIds.add(skill.id)
            result += skill
        }

        return result
    }

    actual fun saveAll(skills: List<SkillDefinition>) {
        skillsDir.mkdirs()
        val keptIds = skills.map { it.id }.toSet()

        // Delete tracked directories for removed skills
        idToDir.entries.removeAll { (id, dir) ->
            if (id !in keptIds) { dir.deleteRecursively(); true } else false
        }

        // Write/update each skill
        for (skill in skills) {
            val dir = idToDir[skill.id] ?: run {
                val slug = SkillMarkdown.slugify(skill.name)
                uniqueDir(slug).also { it.mkdirs(); idToDir[skill.id] = it }
            }
            val skillFile = File(dir, "SKILL.md")
            runCatching { skillFile.writeText(SkillMarkdown.serialize(skill)) }
        }
    }

    actual fun importFromFolder(folderPath: String): FolderImportResult {
        val folder = File(folderPath)
        if (!folder.isDirectory) return FolderImportResult(emptyList(), 0, listOf("No es una carpeta válida"))

        skillsDir.mkdirs()
        val imported = mutableListOf<SkillDefinition>()
        val errors = mutableListOf<String>()
        var skipped = 0

        // Case 1: The selected folder itself IS a skill (has SKILL.md at root)
        val rootSkillFile = File(folder, "SKILL.md")
        if (rootSkillFile.exists()) {
            processImportDir(folder, imported, errors)
            return FolderImportResult(imported, skipped, errors)
        }

        // Case 2: The folder contains skill subdirectories (each with SKILL.md)
        folder.listFiles { f -> f.isDirectory }?.forEach { subDir ->
            val skillFile = File(subDir, "SKILL.md")
            if (!skillFile.exists()) { skipped++; return@forEach }
            processImportDir(subDir, imported, errors)
        }

        // Case 3: Loose *.md files in folder root (simple format, no SKILL.md present)
        folder.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { file ->
            processImportFile(file, fallbackName = file.nameWithoutExtension, imported, errors)
        }

        return FolderImportResult(imported, skipped, errors)
    }

    /**
     * Returns the absolute path to the scripts directory for a given skill id,
     * or null if the skill is not tracked or has no scripts dir.
     */
    fun scriptsDir(skillId: String): File? {
        val dir = idToDir[skillId] ?: return null
        val scripts = File(dir, "scripts")
        return if (scripts.isDirectory) scripts else null
    }

    /**
     * Returns the absolute path to the skill directory for a given skill id.
     */
    fun skillDir(skillId: String): File? = idToDir[skillId]

    actual fun skillDirPath(skillId: String): String? = idToDir[skillId]?.absolutePath

    // ─────────────────────────────────────────────
    // Import: directory with SKILL.md + optional scripts/
    // ─────────────────────────────────────────────

    private fun processImportDir(
        sourceDir: File,
        imported: MutableList<SkillDefinition>,
        errors: MutableList<String>
    ) {
        val skillFile = File(sourceDir, "SKILL.md")
        val text = runCatching { skillFile.readText() }.getOrElse {
            errors += "No se pudo leer ${skillFile.path}: ${it.message}"
            return
        }
        var parsed = SkillMarkdown.parse(text, fallbackName = sourceDir.name)
        if (parsed == null) {
            errors += "Markdown inválido: ${skillFile.path}"
            return
        }

        val slug = SkillMarkdown.slugify(parsed.name)

        // Dedup: same base id → reuse existing dir (update, not duplicate)
        val baseId = "custom_$slug"
        val (finalId, destDir) = if (baseId in idToDir) {
            baseId to idToDir[baseId]!!
        } else {
            val newId = freshId(parsed.name, idToDir.keys.toSet())
            newId to uniqueDir(slug).also { it.mkdirs() }
        }

        // Copy scripts/ directory if present
        val sourceScripts = File(sourceDir, "scripts")
        val destScripts = File(destDir, "scripts")
        if (sourceScripts.isDirectory) {
            destScripts.mkdirs()
            sourceScripts.listFiles()?.forEach { srcFile ->
                if (srcFile.isFile) {
                    val destFile = File(destScripts, srcFile.name)
                    runCatching { srcFile.copyTo(destFile, overwrite = true) }.onFailure {
                        errors += "No se pudo copiar script ${srcFile.name}: ${it.message}"
                    }
                    // Make shell scripts executable
                    if (srcFile.name.endsWith(".sh")) {
                        runCatching { destFile.setExecutable(true) }
                    }
                }
            }
        }

        // Copy additional .md files (companion docs like visual-companion.md)
        sourceDir.listFiles { f -> f.isFile && f.extension == "md" && f.name != "SKILL.md" }
            ?.forEach { extraMd ->
                val dest = File(destDir, extraMd.name)
                runCatching { extraMd.copyTo(dest, overwrite = true) }
            }

        // Auto-generate SkillScript entries from scripts/ if the SKILL.md doesn't
        // already define them
        if (parsed.scripts.isEmpty() && sourceScripts.isDirectory) {
            val autoScripts = buildScriptsFromDir(destScripts)
            if (autoScripts.isNotEmpty()) {
                parsed = parsed.copy(scripts = autoScripts)
            }
        }

        val skill = parsed.copy(id = finalId)
        val destSkillFile = File(destDir, "SKILL.md")
        runCatching { destSkillFile.writeText(SkillMarkdown.serialize(skill)) }.onFailure {
            errors += "No se pudo escribir ${destSkillFile.path}: ${it.message}"
            return
        }
        idToDir[finalId] = destDir
        imported += skill
    }

    // ─────────────────────────────────────────────
    // Import: loose .md file (legacy/simple)
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

        val (finalId, destDir) = if (baseId in idToDir) {
            baseId to idToDir[baseId]!!
        } else {
            val newId = freshId(parsed.name, idToDir.keys.toSet())
            newId to uniqueDir(slug).also { it.mkdirs() }
        }

        val skill = parsed.copy(id = finalId)
        val destFile = File(destDir, "SKILL.md")
        runCatching { destFile.writeText(SkillMarkdown.serialize(skill)) }.onFailure {
            errors += "No se pudo copiar ${file.name}: ${it.message}"
            return
        }
        idToDir[finalId] = destDir
        imported += skill
    }

    // ─────────────────────────────────────────────
    // Auto-generate SkillScript entries from files in scripts/
    // ─────────────────────────────────────────────

    private fun buildScriptsFromDir(scriptsDir: File): List<SkillScript> {
        val files = scriptsDir.listFiles { f -> f.isFile && isExecutableScript(f) }
            ?: return emptyList()

        return files.map { file ->
            val scriptName = file.nameWithoutExtension
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            SkillScript(
                name = scriptName,
                description = "Ejecutar ${file.name}",
                command = file.absolutePath,
                params = emptyList()
            )
        }
    }

    private fun isExecutableScript(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("sh", "bash", "py", "rb", "js", "cjs", "mjs", "ts", "pl")
            || file.canExecute()
    }

    // ─────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────

    private fun freshId(name: String, existingIds: Set<String>): String {
        val base = "custom_${SkillMarkdown.slugify(name)}"
        if (base !in existingIds) return base
        var i = 2
        while ("$base-$i" in existingIds) i++
        return "$base-$i"
    }

    private fun uniqueDir(slug: String): File {
        var candidate = File(skillsDir, slug)
        var suffix = 2
        while (candidate.exists() || idToDir.values.contains(candidate)) {
            candidate = File(skillsDir, "$slug-$suffix")
            suffix++
        }
        return candidate
    }
}

actual fun createSkillFileStore(): SkillFileStore = SkillFileStore()
