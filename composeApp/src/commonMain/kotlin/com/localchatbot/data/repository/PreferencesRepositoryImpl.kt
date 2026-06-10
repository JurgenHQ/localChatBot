package com.localchatbot.data.repository

import com.localchatbot.core.storage.SkillFileStore
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionMode
import com.localchatbot.domain.model.InstalledSkill
import com.localchatbot.domain.model.PromptTemplate
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PreferencesRepositoryImpl(
    private val settings: Settings,
    private val skillFileStore: SkillFileStore
) : PreferencesRepository {

    private val templatesJson = Json { ignoreUnknownKeys = true }
    private val templatesSerializer = ListSerializer(PromptTemplate.serializer())
    private val skillsSerializer = ListSerializer(InstalledSkill.serializer())
    private val customSkillsSerializer = ListSerializer(SkillDefinition.serializer())

    private val _state = MutableStateFlow(load())
    override val preferences: StateFlow<AppPreferences> = _state.asStateFlow()

    override suspend fun current(): AppPreferences = _state.value

    override suspend fun updateConnection(config: ConnectionConfig) {
        settings.putString(KEY_CONN_MODE, config.mode.name)
        settings.putString(KEY_IP, config.ip)
        settings.putString(KEY_PORT, config.port)
        settings.putString(KEY_MODEL, config.model)
        settings.putString(KEY_DIRECT_URL, config.directUrl)
        _state.value = _state.value.copy(connection = config)
    }

    override suspend fun updateThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME, mode.name)
        _state.value = _state.value.copy(themeMode = mode)
    }

    override suspend fun updateAccent(seed: Long) {
        settings.putLong(KEY_ACCENT, seed)
        _state.value = _state.value.copy(accentSeed = seed)
    }

    override suspend fun markOnboardingDone() {
        settings.putBoolean(KEY_ONBOARDED, true)
        _state.value = _state.value.copy(onboardingDone = true)
    }

    override suspend fun updateTavilyApiKey(value: String) {
        settings.putString(KEY_TAVILY, value)
        _state.value = _state.value.copy(tavilyApiKey = value)
    }

    override suspend fun updateDefaultSystemPrompt(value: String) {
        settings.putString(KEY_SYSTEM_PROMPT, value)
        _state.value = _state.value.copy(defaultSystemPrompt = value)
    }

    override suspend fun setPromptTemplates(templates: List<PromptTemplate>) {
        settings.putString(KEY_TEMPLATES, templatesJson.encodeToString(templatesSerializer, templates))
        _state.value = _state.value.copy(promptTemplates = templates)
    }

    override suspend fun updateImageServiceUrl(value: String) {
        settings.putString(KEY_IMAGE_URL, value)
        _state.value = _state.value.copy(imageServiceUrl = value)
    }

    override suspend fun updateFsWorkspaceDir(value: String?) {
        if (value.isNullOrBlank()) {
            settings.remove(KEY_FS_WORKSPACE)
            _state.value = _state.value.copy(fsWorkspaceDir = null)
        } else {
            settings.putString(KEY_FS_WORKSPACE, value)
            _state.value = _state.value.copy(fsWorkspaceDir = value)
        }
    }

    override suspend fun updateFsYoloMode(value: Boolean) {
        settings.putBoolean(KEY_FS_YOLO, value)
        _state.value = _state.value.copy(fsYoloMode = value)
    }

    override suspend fun updateFsAllowOutsideWorkspace(value: Boolean) {
        settings.putBoolean(KEY_FS_ALLOW_OUTSIDE, value)
        _state.value = _state.value.copy(fsAllowOutsideWorkspace = value)
    }

    override suspend fun setInstalledSkills(skills: List<InstalledSkill>) {
        settings.putString(KEY_INSTALLED_SKILLS, templatesJson.encodeToString(skillsSerializer, skills))
        _state.value = _state.value.copy(installedSkills = skills)
    }

    override suspend fun setCustomSkills(skills: List<SkillDefinition>) {
        if (skillFileStore.isAvailable) {
            skillFileStore.saveAll(skills)
        } else {
            settings.putString(KEY_CUSTOM_SKILLS, templatesJson.encodeToString(customSkillsSerializer, skills))
        }
        _state.value = _state.value.copy(customSkills = skills)
    }

    override suspend fun refreshCustomSkills() {
        if (!skillFileStore.isAvailable) return
        val skills = skillFileStore.loadAll()
        _state.value = _state.value.copy(customSkills = skills)
    }

    override suspend fun reset() {
        listOf(
            KEY_CONN_MODE, KEY_IP, KEY_PORT, KEY_MODEL, KEY_DIRECT_URL,
            KEY_THEME, KEY_ACCENT, KEY_ONBOARDED,
            KEY_TAVILY, KEY_SYSTEM_PROMPT, KEY_TEMPLATES, KEY_IMAGE_URL,
            KEY_FS_WORKSPACE, KEY_FS_YOLO, KEY_FS_ALLOW_OUTSIDE,
            KEY_INSTALLED_SKILLS, KEY_CUSTOM_SKILLS
        ).forEach(settings::remove)
        _state.value = AppPreferences.Default
    }

    private fun load(): AppPreferences {
        val default = AppPreferences.Default
        return AppPreferences(
            connection = ConnectionConfig(
                mode = runCatching {
                    ConnectionMode.valueOf(settings.getString(KEY_CONN_MODE, default.connection.mode.name))
                }.getOrDefault(default.connection.mode),
                ip = settings.getString(KEY_IP, default.connection.ip),
                port = settings.getString(KEY_PORT, default.connection.port),
                model = settings.getString(KEY_MODEL, default.connection.model),
                directUrl = settings.getString(KEY_DIRECT_URL, default.connection.directUrl)
            ),
            themeMode = runCatching {
                ThemeMode.valueOf(settings.getString(KEY_THEME, default.themeMode.name))
            }.getOrDefault(default.themeMode),
            accentSeed = settings.getLong(KEY_ACCENT, default.accentSeed),
            onboardingDone = settings.getBoolean(KEY_ONBOARDED, default.onboardingDone),
            tavilyApiKey = settings.getString(KEY_TAVILY, default.tavilyApiKey),
            defaultSystemPrompt = settings.getString(KEY_SYSTEM_PROMPT, default.defaultSystemPrompt),
            promptTemplates = runCatching {
                val raw = settings.getStringOrNull(KEY_TEMPLATES) ?: return@runCatching emptyList()
                templatesJson.decodeFromString(templatesSerializer, raw)
            }.getOrDefault(emptyList()),
            imageServiceUrl = settings.getString(KEY_IMAGE_URL, default.imageServiceUrl),
            fsWorkspaceDir = settings.getStringOrNull(KEY_FS_WORKSPACE),
            fsYoloMode = settings.getBoolean(KEY_FS_YOLO, default.fsYoloMode),
            fsAllowOutsideWorkspace = settings.getBoolean(KEY_FS_ALLOW_OUTSIDE, default.fsAllowOutsideWorkspace),
            installedSkills = runCatching {
                val raw = settings.getStringOrNull(KEY_INSTALLED_SKILLS) ?: return@runCatching emptyList()
                templatesJson.decodeFromString(skillsSerializer, raw)
            }.getOrDefault(emptyList()),
            customSkills = loadCustomSkills()
        )
    }

    private fun loadCustomSkills(): List<SkillDefinition> {
        if (!skillFileStore.isAvailable) {
            return runCatching {
                val raw = settings.getStringOrNull(KEY_CUSTOM_SKILLS) ?: return@runCatching emptyList()
                templatesJson.decodeFromString(customSkillsSerializer, raw)
            }.getOrDefault(emptyList())
        }
        // Migrate existing JSON-stored skills to files (runs once)
        val jsonRaw = settings.getStringOrNull(KEY_CUSTOM_SKILLS)
        if (jsonRaw != null) {
            runCatching {
                val existing = templatesJson.decodeFromString(customSkillsSerializer, jsonRaw)
                if (existing.isNotEmpty()) {
                    val current = skillFileStore.loadAll()
                    val merged = (current + existing).distinctBy { it.id }
                    skillFileStore.saveAll(merged)
                }
                settings.remove(KEY_CUSTOM_SKILLS)
            }
        }
        return skillFileStore.loadAll()
    }

    private companion object {
        const val KEY_CONN_MODE = "conn_mode"
        const val KEY_IP = "conn_ip"
        const val KEY_PORT = "conn_port"
        const val KEY_MODEL = "conn_model"
        const val KEY_DIRECT_URL = "conn_direct_url"
        const val KEY_THEME = "theme_mode"
        const val KEY_ACCENT = "accent_seed"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_TAVILY = "tavily_api_key"
        const val KEY_SYSTEM_PROMPT = "default_system_prompt"
        const val KEY_TEMPLATES = "prompt_templates"
        const val KEY_IMAGE_URL = "image_service_url"
        const val KEY_FS_WORKSPACE = "fs_workspace_dir"
        const val KEY_FS_YOLO = "fs_yolo_mode"
        const val KEY_FS_ALLOW_OUTSIDE = "fs_allow_outside_workspace"
        const val KEY_INSTALLED_SKILLS = "installed_skills"
        const val KEY_CUSTOM_SKILLS = "custom_skills"
    }
}
