package com.localchatbot.domain.skill

import com.localchatbot.domain.model.ScriptParam
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.model.SkillScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object SkillMarkdown {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scriptsSerializer = ListSerializer(SkillScript.serializer())

    // ─────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────

    fun parse(text: String, fallbackName: String? = null): SkillDefinition? {
        val (frontmatter, body) = splitFrontmatter(text)
        val keys = parseFrontmatter(frontmatter)
        val bodyTrimmed = body.trim()

        val name = keys["name"]?.unquote()
            ?: extractFirstHeading(bodyTrimmed)
            ?: fallbackName
            ?: return null.also { if (bodyTrimmed.isEmpty()) return null }

        if (name.isBlank() && bodyTrimmed.isEmpty()) return null

        val resolvedName = name.ifBlank { fallbackName ?: return null }

        val description = keys["description"]?.unquote()
            ?: extractFirstLine(bodyTrimmed, maxChars = 120)
            ?: resolvedName

        val fullDescription = keys["full_description"]?.let { v ->
            if (v.startsWith("\"")) runCatching { json.decodeFromString<String>(v) }.getOrElse { v.unquote() }
            else v.unquote()
        } ?: description

        val scripts: List<SkillScript> = keys["scripts"]?.let { v ->
            if (v.startsWith("[")) runCatching { json.decodeFromString(scriptsSerializer, v) }.getOrElse { emptyList() }
            else emptyList()
        } ?: emptyList()

        val id = keys["id"]?.unquote()?.takeIf { it.isNotBlank() } ?: ""

        return SkillDefinition(
            id = id,
            name = resolvedName,
            description = description,
            fullDescription = fullDescription,
            systemPromptAddition = bodyTrimmed,
            scripts = scripts
        )
    }

    fun serialize(skill: SkillDefinition): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("id: ${skill.id}")
        sb.appendLine("name: ${skill.name}")
        sb.appendLine("description: ${skill.description}")
        if (skill.fullDescription.isNotBlank() && skill.fullDescription != skill.description) {
            val encoded = buildString {
                append('"')
                append(skill.fullDescription.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"))
                append('"')
            }
            sb.appendLine("full_description: $encoded")
        }
        if (skill.scripts.isNotEmpty()) {
            sb.appendLine("scripts: ${json.encodeToString(scriptsSerializer, skill.scripts)}")
        }
        sb.appendLine("---")
        sb.append(skill.systemPromptAddition)
        return sb.toString()
    }

    fun slugify(name: String): String {
        val slug = name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifEmpty { "skill" }
    }

    // ─────────────────────────────────────────────
    // Parsing helpers
    // ─────────────────────────────────────────────

    private fun splitFrontmatter(text: String): Pair<String, String> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return "" to trimmed

        val afterOpen = trimmed.removePrefix("---")
        val closeIdx = afterOpen.indexOf("\n---")
        if (closeIdx == -1) return "" to trimmed

        val frontmatter = afterOpen.substring(0, closeIdx).trim()
        val body = afterOpen.substring(closeIdx + 4) // skip "\n---"
        return frontmatter to body
    }

    private fun parseFrontmatter(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun extractFirstHeading(body: String): String? {
        for (line in body.lines()) {
            val trimmed = line.trimStart('#').trim()
            if (line.startsWith("#") && trimmed.isNotEmpty()) return trimmed
        }
        return null
    }

    private fun extractFirstLine(body: String, maxChars: Int): String? {
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                return if (trimmed.length > maxChars) trimmed.substring(0, maxChars) + "…" else trimmed
            }
        }
        return null
    }

    private fun String.unquote(): String =
        if (startsWith('"') && endsWith('"') && length >= 2) substring(1, length - 1) else this
}
