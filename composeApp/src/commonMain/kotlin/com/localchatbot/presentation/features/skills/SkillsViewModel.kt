package com.localchatbot.presentation.features.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.storage.SkillFileStore
import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.InstalledSkill
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.model.SkillScript
import com.localchatbot.domain.model.SkillsExport
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.skill.SkillCatalog
import com.localchatbot.domain.skill.SkillMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class SkillUiItem(
    val definition: SkillDefinition,
    val isInstalled: Boolean,
    val isEnabled: Boolean,
    val isCustom: Boolean
)

data class SkillsUiState(
    val builtInItems: List<SkillUiItem> = emptyList(),
    val customItems: List<SkillUiItem> = emptyList(),
    val detailSkillId: String? = null,
    val showCreateSheet: Boolean = false,
    val editingSkill: SkillDefinition? = null,
    val showImportExport: Boolean = false,
    val exportJson: String = "",
    val importError: String? = null,
    val folderImportResult: String? = null
) {
    val allItems: List<SkillUiItem> get() = builtInItems + customItems
    val detailItem: SkillUiItem? get() = allItems.firstOrNull { it.definition.id == detailSkillId }
}

class SkillsViewModel(
    private val preferences: PreferencesRepository,
    private val skillFileStore: SkillFileStore
) : ViewModel() {

    private val _detailSkillId = MutableStateFlow<String?>(null)
    private val _showCreateSheet = MutableStateFlow(false)
    private val _editingSkill = MutableStateFlow<SkillDefinition?>(null)
    private val _showImportExport = MutableStateFlow(false)
    private val _importError = MutableStateFlow<String?>(null)
    private val _folderImportResult = MutableStateFlow<String?>(null)

    private val exportJson = Json { prettyPrint = true }
    private val importJson = Json { ignoreUnknownKeys = true }

    private data class UiFlags(
        val detailId: String?,
        val showCreate: Boolean,
        val editing: SkillDefinition?,
        val showImportExport: Boolean,
        val importError: String?,
        val folderImportResult: String?
    )

    private val _uiFlags = combine(
        _detailSkillId, _showCreateSheet, _editingSkill, _showImportExport,
        _importError, _folderImportResult
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        UiFlags(
            arr[0] as String?,
            arr[1] as Boolean,
            arr[2] as SkillDefinition?,
            arr[3] as Boolean,
            arr[4] as String?,
            arr[5] as String?
        )
    }

    val state: StateFlow<SkillsUiState> = combine(
        preferences.preferences,
        _uiFlags
    ) { prefs, flags ->
        val installed = prefs.installedSkills
        val customDefs = prefs.customSkills

        fun makeItem(def: SkillDefinition, isCustom: Boolean): SkillUiItem {
            val entry = installed.firstOrNull { it.skillId == def.id }
            return SkillUiItem(
                definition = def,
                isInstalled = entry != null,
                isEnabled = entry?.enabled == true,
                isCustom = isCustom
            )
        }

        val exportPayload = if (customDefs.isNotEmpty()) {
            exportJson.encodeToString(SkillsExport.serializer(), SkillsExport(skills = customDefs))
        } else ""

        SkillsUiState(
            builtInItems = SkillCatalog.all.map { makeItem(it, false) },
            customItems = customDefs.map { makeItem(it, true) },
            detailSkillId = flags.detailId,
            showCreateSheet = flags.showCreate,
            editingSkill = flags.editing,
            showImportExport = flags.showImportExport,
            exportJson = exportPayload,
            importError = flags.importError,
            folderImportResult = flags.folderImportResult
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SkillsUiState())

    fun openDetail(skillId: String) { _detailSkillId.value = skillId }
    fun closeDetail() { _detailSkillId.value = null }

    fun openCreateSheet() { _editingSkill.value = null; _showCreateSheet.value = true }
    fun openEditSheet(skill: SkillDefinition) { _editingSkill.value = skill; _showCreateSheet.value = true }
    fun closeCreateSheet() { _showCreateSheet.value = false; _editingSkill.value = null }

    fun openImportExport() { _showImportExport.value = true; _importError.value = null }
    fun closeImportExport() { _showImportExport.value = false; _importError.value = null }

    fun saveCustomSkill(
        name: String,
        description: String,
        fullDescription: String,
        systemPromptAddition: String,
        scripts: List<SkillScript> = emptyList()
    ) = viewModelScope.launch {
        val trimName = name.trim()
        val trimDesc = description.trim()
        if (trimName.isBlank() || trimDesc.isBlank()) return@launch

        val existing = _editingSkill.value
        val prefs = preferences.current()
        val currentCustom = prefs.customSkills.toMutableList()

        if (existing != null) {
            val updated = existing.copy(
                name = trimName,
                description = trimDesc,
                fullDescription = fullDescription.trim(),
                systemPromptAddition = systemPromptAddition.trim(),
                scripts = scripts
            )
            val idx = currentCustom.indexOfFirst { it.id == existing.id }
            if (idx >= 0) currentCustom[idx] = updated else currentCustom.add(updated)
        } else {
            currentCustom.add(
                SkillDefinition(
                    id = "custom_${newId()}",
                    name = trimName,
                    description = trimDesc,
                    fullDescription = fullDescription.trim(),
                    systemPromptAddition = systemPromptAddition.trim(),
                    scripts = scripts
                )
            )
        }
        preferences.setCustomSkills(currentCustom)
        closeCreateSheet()
    }

    fun deleteCustomSkill(skillId: String) = viewModelScope.launch {
        val prefs = preferences.current()
        preferences.setCustomSkills(prefs.customSkills.filter { it.id != skillId })
        preferences.setInstalledSkills(prefs.installedSkills.filter { it.skillId != skillId })
        if (_detailSkillId.value == skillId) _detailSkillId.value = null
    }

    fun install(skillId: String) = viewModelScope.launch {
        val current = preferences.current().installedSkills.toMutableList()
        if (current.none { it.skillId == skillId }) {
            current.add(InstalledSkill(skillId = skillId, enabled = true))
            preferences.setInstalledSkills(current)
        }
    }

    fun uninstall(skillId: String) = viewModelScope.launch {
        val updated = preferences.current().installedSkills.filter { it.skillId != skillId }
        preferences.setInstalledSkills(updated)
    }

    fun toggleEnabled(skillId: String, enabled: Boolean) = viewModelScope.launch {
        val updated = preferences.current().installedSkills.map { skill ->
            if (skill.skillId == skillId) skill.copy(enabled = enabled) else skill
        }
        preferences.setInstalledSkills(updated)
    }

    fun clearFolderImportResult() { _folderImportResult.value = null }

    fun importMarkdown(text: String) = viewModelScope.launch {
        _importError.value = null
        val parsed = SkillMarkdown.parse(text)
        if (parsed == null || (parsed.name.isBlank() && parsed.systemPromptAddition.isBlank())) {
            _importError.value = "Markdown inválido: se necesita al menos nombre o contenido."
            return@launch
        }

        val builtInIds = SkillCatalog.all.map { it.id }.toSet()
        val prefs = preferences.current()
        val freshId = freshId(parsed.name, prefs.customSkills.map { it.id }.toSet() + builtInIds)
        val toAdd = parsed.copy(id = freshId)

        val merged = prefs.customSkills.toMutableList()
        val existing = merged.indexOfFirst { it.id == toAdd.id }
        if (existing >= 0) merged[existing] = toAdd else merged.add(toAdd)
        preferences.setCustomSkills(merged)
        _showImportExport.value = false
    }

    fun importFromFolder(path: String) = viewModelScope.launch {
        if (!skillFileStore.isAvailable) return@launch
        _folderImportResult.value = null
        val result = withContext(Dispatchers.Default) { skillFileStore.importFromFolder(path) }

        if (result.imported.isNotEmpty()) {
            preferences.refreshCustomSkills()
        }

        val msg = buildString {
            if (result.imported.isNotEmpty()) append("${result.imported.size} importada${if (result.imported.size != 1) "s" else ""}")
            if (result.skipped > 0) append(", ${result.skipped} omitida${if (result.skipped != 1) "s" else ""}")
            if (result.errors.isNotEmpty()) append(", ${result.errors.size} con error")
        }
        _folderImportResult.value = msg.ifBlank { "No se encontraron skills." }
    }

    private fun freshId(name: String, existingIds: Set<String>): String {
        val base = "custom_${SkillMarkdown.slugify(name)}"
        if (base !in existingIds) return base
        var i = 2
        while ("$base-$i" in existingIds) i++
        return "$base-$i"
    }

    fun importJson(jsonText: String) = viewModelScope.launch {
        _importError.value = null
        val parsed = runCatching {
            importJson.decodeFromString(SkillsExport.serializer(), jsonText)
        }.getOrElse {
            _importError.value = "JSON inválido: ${it.message?.take(80)}"
            return@launch
        }

        val builtInIds = SkillCatalog.all.map { it.id }.toSet()
        val toImport = parsed.skills.filter { it.id !in builtInIds && it.name.isNotBlank() }
        if (toImport.isEmpty()) {
            _importError.value = "No se encontraron skills válidos para importar."
            return@launch
        }

        val prefs = preferences.current()
        val merged = prefs.customSkills.toMutableList()
        toImport.forEach { incoming ->
            val idx = merged.indexOfFirst { it.id == incoming.id }
            if (idx >= 0) merged[idx] = incoming else merged.add(incoming)
        }
        preferences.setCustomSkills(merged)
        _showImportExport.value = false
    }
}
