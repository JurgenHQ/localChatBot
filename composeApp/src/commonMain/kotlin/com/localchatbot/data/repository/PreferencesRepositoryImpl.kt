package com.localchatbot.data.repository

import com.localchatbot.core.storage.SkillFileStore
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionProfile
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.InstalledSkill
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.domain.model.PromptTemplate
import com.localchatbot.domain.model.ScheduledTask
import com.localchatbot.domain.model.SkillDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.localchatbot.domain.model.SettingsExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class PreferencesRepositoryImpl(
    private val settings: Settings,
    private val skillFileStore: SkillFileStore
) : PreferencesRepository {

    private val templatesJson = Json { ignoreUnknownKeys = true }
    private val exportFormat = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val templatesSerializer = ListSerializer(PromptTemplate.serializer())
    private val skillsSerializer = ListSerializer(InstalledSkill.serializer())
    private val customSkillsSerializer = ListSerializer(SkillDefinition.serializer())
    private val mcpSerializer = ListSerializer(McpServerConfig.serializer())
    private val scheduledTasksSerializer = ListSerializer(ScheduledTask.serializer())
    private val sessionAgentModesSerializer = MapSerializer(String.serializer(), String.serializer())
    private val compactBoundariesSerializer =
        MapSerializer(String.serializer(), com.localchatbot.domain.model.CompactBoundary.serializer())
    private val connectionProfilesSerializer = ListSerializer(ConnectionProfile.serializer())

    private val _state = MutableStateFlow(load())
    override val preferences: StateFlow<AppPreferences> = _state.asStateFlow()

    override suspend fun current(): AppPreferences = _state.value

    override suspend fun updateConnection(config: ConnectionConfig) {
        val current = _state.value
        val updated = current.connectionProfiles.map {
            if (it.id == current.activeConnectionProfileId) it.copy(config = config) else it
        }
        persistConnectionProfiles(updated)
        _state.value = current.copy(connectionProfiles = updated)
    }

    override suspend fun setConnectionProfiles(profiles: List<ConnectionProfile>) {
        val capped = profiles.take(3)
        persistConnectionProfiles(capped)
        val activeStillValid = capped.any { it.id == _state.value.activeConnectionProfileId }
        val nextActive = if (activeStillValid) _state.value.activeConnectionProfileId else capped.firstOrNull()?.id.orEmpty()
        if (!activeStillValid) settings.putString(KEY_ACTIVE_CONNECTION_PROFILE, nextActive)
        _state.value = _state.value.copy(connectionProfiles = capped, activeConnectionProfileId = nextActive)
    }

    override suspend fun setActiveConnectionProfile(id: String) {
        if (_state.value.connectionProfiles.none { it.id == id }) return
        settings.putString(KEY_ACTIVE_CONNECTION_PROFILE, id)
        _state.value = _state.value.copy(activeConnectionProfileId = id)
    }

    private fun persistConnectionProfiles(profiles: List<ConnectionProfile>) {
        settings.putString(KEY_CONNECTION_PROFILES, templatesJson.encodeToString(connectionProfilesSerializer, profiles))
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

    override suspend fun updateEmbeddingsModel(value: String) {
        settings.putString(KEY_EMBEDDINGS_MODEL, value)
        _state.value = _state.value.copy(embeddingsModel = value)
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

    override suspend fun updateFsPreviewEdits(value: Boolean) {
        settings.putBoolean(KEY_FS_PREVIEW_EDITS, value)
        _state.value = _state.value.copy(fsPreviewEdits = value)
    }

    override suspend fun updateAgentMode(value: com.localchatbot.domain.model.AgentMode) {
        settings.putString(KEY_AGENT_MODE, value.name)
        _state.value = _state.value.copy(agentMode = value)
    }

    override suspend fun updateSessionAgentMode(sessionId: String, value: com.localchatbot.domain.model.AgentMode) {
        val next = _state.value.sessionAgentModes + (sessionId to value)
        persistSessionAgentModes(next)
        _state.value = _state.value.copy(sessionAgentModes = next)
    }

    override suspend fun clearSessionAgentModes() {
        persistSessionAgentModes(emptyMap())
        _state.value = _state.value.copy(sessionAgentModes = emptyMap())
    }

    override suspend fun updateSessionCompactBoundary(
        sessionId: String,
        boundary: com.localchatbot.domain.model.CompactBoundary?
    ) {
        val next = if (boundary == null) {
            _state.value.sessionCompactBoundaries - sessionId
        } else {
            _state.value.sessionCompactBoundaries + (sessionId to boundary)
        }
        settings.putString(
            KEY_SESSION_COMPACT_BOUNDARIES,
            templatesJson.encodeToString(compactBoundariesSerializer, next)
        )
        _state.value = _state.value.copy(sessionCompactBoundaries = next)
    }

    private fun persistSessionAgentModes(modes: Map<String, com.localchatbot.domain.model.AgentMode>) {
        settings.putString(
            KEY_SESSION_AGENT_MODES,
            templatesJson.encodeToString(sessionAgentModesSerializer, modes.mapValues { it.value.name })
        )
    }

    override suspend fun setInstalledSkills(skills: List<InstalledSkill>) {
        settings.putString(KEY_INSTALLED_SKILLS, templatesJson.encodeToString(skillsSerializer, skills))
        _state.value = _state.value.copy(installedSkills = skills)
    }

    /**
     * El `withContext` no es decorativo: estos dos métodos recorren el disco (una carpeta
     * con su SKILL.md por skill) y los llama un `viewModelScope.launch`, que en Desktop
     * corre en el EDT — sin el salto, guardar o refrescar skills bloqueaba la UI.
     */
    override suspend fun setCustomSkills(skills: List<SkillDefinition>) {
        if (skillFileStore.isAvailable) {
            withContext(Dispatchers.Default) { skillFileStore.saveAll(skills) }
        } else {
            settings.putString(KEY_CUSTOM_SKILLS, templatesJson.encodeToString(customSkillsSerializer, skills))
        }
        _state.value = _state.value.copy(customSkills = skills)
    }

    override suspend fun refreshCustomSkills() {
        if (!skillFileStore.isAvailable) return
        val skills = withContext(Dispatchers.Default) { skillFileStore.loadAll() }
        _state.value = _state.value.copy(customSkills = skills)
    }

    override suspend fun setMcpServers(servers: List<McpServerConfig>) {
        settings.putString(KEY_MCP_SERVERS, templatesJson.encodeToString(mcpSerializer, servers))
        _state.value = _state.value.copy(mcpServers = servers)
    }

    override suspend fun setScheduledTasks(tasks: List<ScheduledTask>) {
        settings.putString(KEY_SCHEDULED_TASKS, templatesJson.encodeToString(scheduledTasksSerializer, tasks))
        _state.value = _state.value.copy(scheduledTasks = tasks)
    }

    override suspend fun exportJson(): String {
        val export = SettingsExport(
            connectionProfiles = _state.value.connectionProfiles,
            activeConnectionProfileId = _state.value.activeConnectionProfileId,
            themeMode = _state.value.themeMode,
            accentSeed = _state.value.accentSeed,
            tavilyApiKey = _state.value.tavilyApiKey,
            defaultSystemPrompt = _state.value.defaultSystemPrompt,
            promptTemplates = _state.value.promptTemplates,
            imageServiceUrl = _state.value.imageServiceUrl,
            embeddingsModel = _state.value.embeddingsModel,
            fsWorkspaceDir = _state.value.fsWorkspaceDir,
            fsYoloMode = _state.value.fsYoloMode,
            fsAllowOutsideWorkspace = _state.value.fsAllowOutsideWorkspace,
            installedSkills = _state.value.installedSkills,
            customSkills = _state.value.customSkills,
            mcpServers = _state.value.mcpServers,
            scheduledTasks = _state.value.scheduledTasks
        )
        return exportFormat.encodeToString(SettingsExport.serializer(), export)
    }

    override suspend fun importJson(json: String) {
        try {
            val export = templatesJson.decodeFromString(SettingsExport.serializer(), json)

            val profiles = export.connectionProfiles.ifEmpty {
                val legacy = export.connection ?: ConnectionConfig()
                listOf(ConnectionProfile(id = newId(), name = "Perfil 1", config = legacy))
            }.take(3)
            setConnectionProfiles(profiles)
            setActiveConnectionProfile(export.activeConnectionProfileId.ifBlank { profiles.first().id })
            updateThemeMode(export.themeMode)
            updateAccent(export.accentSeed)
            updateTavilyApiKey(export.tavilyApiKey)
            updateDefaultSystemPrompt(export.defaultSystemPrompt)
            setPromptTemplates(export.promptTemplates)
            updateImageServiceUrl(export.imageServiceUrl)
            updateEmbeddingsModel(export.embeddingsModel)
            updateFsWorkspaceDir(export.fsWorkspaceDir)
            updateFsYoloMode(export.fsYoloMode)
            updateFsAllowOutsideWorkspace(export.fsAllowOutsideWorkspace)
            setInstalledSkills(export.installedSkills)
            setCustomSkills(export.customSkills)
            setMcpServers(export.mcpServers)
            setScheduledTasks(export.scheduledTasks)
        } catch (e: Exception) {
            throw Exception("Error parsing settings JSON: ${e.message}")
        }
    }

    override suspend fun updateRemoteAccess(enabled: Boolean, port: Int, pin: String) {
        settings.putBoolean(KEY_REMOTE_ENABLED, enabled)
        settings.putInt(KEY_REMOTE_PORT, port)
        settings.putString(KEY_REMOTE_PIN, pin)
        _state.value = _state.value.copy(
            remoteAccessEnabled = enabled,
            remoteAccessPort = port,
            remoteAccessPin = pin
        )
    }

    override suspend fun updateRemoteViewerUrl(value: String) {
        settings.putString(KEY_REMOTE_VIEWER_URL, value)
        _state.value = _state.value.copy(remoteViewerUrl = value)
    }

    override suspend fun updateDesktopNotifications(value: Boolean) {
        settings.putBoolean(KEY_DESKTOP_NOTIFICATIONS, value)
        _state.value = _state.value.copy(desktopNotificationsEnabled = value)
    }

    override suspend fun updateGenerationParams(params: GenerationParams) {
        settings.putString(KEY_GEN_PARAMS, templatesJson.encodeToString(GenerationParams.serializer(), params))
        _state.value = _state.value.copy(generationParams = params)
    }

    override suspend fun reset() {
        listOf(
            KEY_CONN_MODE, KEY_IP, KEY_PORT, KEY_MODEL, KEY_DIRECT_URL, KEY_HTTPS, KEY_API_KEY,
            KEY_CONNECTION_PROFILES, KEY_ACTIVE_CONNECTION_PROFILE,
            KEY_THEME, KEY_ACCENT, KEY_ONBOARDED,
            KEY_TAVILY, KEY_SYSTEM_PROMPT, KEY_TEMPLATES, KEY_IMAGE_URL, KEY_EMBEDDINGS_MODEL,
            KEY_FS_WORKSPACE, KEY_FS_YOLO, KEY_FS_ALLOW_OUTSIDE, KEY_FS_PREVIEW_EDITS, KEY_AGENT_MODE,
            KEY_SESSION_AGENT_MODES, KEY_SESSION_COMPACT_BOUNDARIES,
            KEY_INSTALLED_SKILLS, KEY_CUSTOM_SKILLS, KEY_MCP_SERVERS, KEY_SCHEDULED_TASKS,
            KEY_REMOTE_ENABLED, KEY_REMOTE_PORT, KEY_REMOTE_PIN, KEY_REMOTE_VIEWER_URL,
            KEY_DESKTOP_NOTIFICATIONS, KEY_GEN_PARAMS
        ).forEach(settings::remove)
        _state.value = AppPreferences.Default
    }

    private fun load(): AppPreferences {
        val default = AppPreferences.Default
        val (profiles, activeId) = loadConnectionProfiles(default)
        return AppPreferences(
            connectionProfiles = profiles,
            activeConnectionProfileId = activeId,
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
            embeddingsModel = settings.getString(KEY_EMBEDDINGS_MODEL, default.embeddingsModel),
            fsWorkspaceDir = settings.getStringOrNull(KEY_FS_WORKSPACE),
            fsYoloMode = settings.getBoolean(KEY_FS_YOLO, default.fsYoloMode),
            fsAllowOutsideWorkspace = settings.getBoolean(KEY_FS_ALLOW_OUTSIDE, default.fsAllowOutsideWorkspace),
            fsPreviewEdits = settings.getBoolean(KEY_FS_PREVIEW_EDITS, default.fsPreviewEdits),
            agentMode = runCatching {
                com.localchatbot.domain.model.AgentMode.valueOf(
                    settings.getString(KEY_AGENT_MODE, default.agentMode.name)
                )
            }.getOrDefault(default.agentMode),
            sessionAgentModes = runCatching {
                val raw = settings.getStringOrNull(KEY_SESSION_AGENT_MODES) ?: return@runCatching emptyMap()
                templatesJson.decodeFromString(sessionAgentModesSerializer, raw)
                    .mapNotNull { (id, name) ->
                        runCatching { id to com.localchatbot.domain.model.AgentMode.valueOf(name) }.getOrNull()
                    }.toMap()
            }.getOrDefault(emptyMap()),
            sessionCompactBoundaries = runCatching {
                val raw = settings.getStringOrNull(KEY_SESSION_COMPACT_BOUNDARIES) ?: return@runCatching emptyMap()
                templatesJson.decodeFromString(compactBoundariesSerializer, raw)
            }.getOrDefault(emptyMap()),
            installedSkills = runCatching {
                val raw = settings.getStringOrNull(KEY_INSTALLED_SKILLS) ?: return@runCatching emptyList()
                templatesJson.decodeFromString(skillsSerializer, raw)
            }.getOrDefault(emptyList()),
            customSkills = loadCustomSkills(),
            mcpServers = runCatching {
                val raw = settings.getStringOrNull(KEY_MCP_SERVERS) ?: return@runCatching emptyList()
                templatesJson.decodeFromString(mcpSerializer, raw)
            }.getOrDefault(emptyList()),
            scheduledTasks = runCatching {
                val raw = settings.getStringOrNull(KEY_SCHEDULED_TASKS) ?: return@runCatching emptyList()
                templatesJson.decodeFromString(scheduledTasksSerializer, raw)
            }.getOrDefault(emptyList()),
            remoteAccessEnabled = settings.getBoolean(KEY_REMOTE_ENABLED, default.remoteAccessEnabled),
            remoteAccessPort = settings.getInt(KEY_REMOTE_PORT, default.remoteAccessPort),
            remoteAccessPin = settings.getString(KEY_REMOTE_PIN, default.remoteAccessPin),
            remoteViewerUrl = settings.getString(KEY_REMOTE_VIEWER_URL, default.remoteViewerUrl),
            desktopNotificationsEnabled = settings.getBoolean(
                KEY_DESKTOP_NOTIFICATIONS, default.desktopNotificationsEnabled
            ),
            generationParams = runCatching {
                val raw = settings.getStringOrNull(KEY_GEN_PARAMS) ?: return@runCatching GenerationParams()
                templatesJson.decodeFromString(GenerationParams.serializer(), raw)
            }.getOrDefault(GenerationParams())
        )
    }

    /**
     * Carga los perfiles de conexión. Si ya existen (`KEY_CONNECTION_PROFILES`), los usa tal
     * cual. Si no (primer arranque tras introducir perfiles), envuelve la conexión legada
     * (single-profile) en un "Perfil 1" y lo persiste de una vez, para que la migración
     * solo corra una vez.
     */
    private fun loadConnectionProfiles(default: AppPreferences): Pair<List<ConnectionProfile>, String> {
        val raw = settings.getStringOrNull(KEY_CONNECTION_PROFILES)
        val existing = raw?.let {
            runCatching { templatesJson.decodeFromString(connectionProfilesSerializer, it) }.getOrNull()
        }
        if (!existing.isNullOrEmpty()) {
            val activeId = settings.getStringOrNull(KEY_ACTIVE_CONNECTION_PROFILE)
                ?.takeIf { id -> existing.any { it.id == id } }
                ?: existing.first().id
            return existing to activeId
        }

        val legacy = loadLegacyConnection(default.connection)
        val profile = ConnectionProfile(id = newId(), name = "Perfil 1", config = legacy)
        settings.putString(KEY_CONNECTION_PROFILES, templatesJson.encodeToString(connectionProfilesSerializer, listOf(profile)))
        settings.putString(KEY_ACTIVE_CONNECTION_PROFILE, profile.id)
        return listOf(profile) to profile.id
    }

    /**
     * Carga la conexión legada (single-profile). Migra la antigua "URL directa" (modo
     * eliminado) hacia host/puerto/https la primera vez, y limpia las llaves obsoletas.
     */
    private fun loadLegacyConnection(default: ConnectionConfig): ConnectionConfig {
        var ip = settings.getString(KEY_IP, default.ip)
        var port = settings.getString(KEY_PORT, default.port)
        var useHttps = settings.getBoolean(KEY_HTTPS, default.useHttps)

        val oldMode = settings.getStringOrNull(KEY_CONN_MODE)
        val oldDirectUrl = settings.getStringOrNull(KEY_DIRECT_URL)
        if (oldMode == "DirectUrl" && !oldDirectUrl.isNullOrBlank() && ip.isBlank()) {
            useHttps = oldDirectUrl.startsWith("https://", ignoreCase = true)
            val hostPort = oldDirectUrl
                .removePrefix("https://").removePrefix("http://")
                .trimEnd('/').removeSuffix("/v1")
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0 && hostPort.substring(colon + 1).all { it.isDigit() }) {
                ip = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1)
            } else {
                ip = hostPort
                port = ""
            }
        }
        // Llaves del modelo de conexión anterior: ya no se usan.
        settings.remove(KEY_CONN_MODE)
        settings.remove(KEY_DIRECT_URL)

        return ConnectionConfig(
            ip = ip,
            port = port,
            useHttps = useHttps,
            model = settings.getString(KEY_MODEL, default.model),
            apiKey = settings.getString(KEY_API_KEY, default.apiKey)
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
        const val KEY_HTTPS = "conn_https"
        const val KEY_API_KEY = "conn_api_key"
        const val KEY_CONNECTION_PROFILES = "connection_profiles"
        const val KEY_ACTIVE_CONNECTION_PROFILE = "active_connection_profile"
        const val KEY_THEME = "theme_mode"
        const val KEY_ACCENT = "accent_seed"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_TAVILY = "tavily_api_key"
        const val KEY_SYSTEM_PROMPT = "default_system_prompt"
        const val KEY_TEMPLATES = "prompt_templates"
        const val KEY_IMAGE_URL = "image_service_url"
        const val KEY_EMBEDDINGS_MODEL = "embeddings_model"
        const val KEY_FS_WORKSPACE = "fs_workspace_dir"
        const val KEY_FS_YOLO = "fs_yolo_mode"
        const val KEY_FS_ALLOW_OUTSIDE = "fs_allow_outside_workspace"
        const val KEY_FS_PREVIEW_EDITS = "fs_preview_edits"
        const val KEY_AGENT_MODE = "agent_mode"
        const val KEY_SESSION_AGENT_MODES = "session_agent_modes"
        const val KEY_SESSION_COMPACT_BOUNDARIES = "session_compact_boundaries"
        const val KEY_INSTALLED_SKILLS = "installed_skills"
        const val KEY_CUSTOM_SKILLS = "custom_skills"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_SCHEDULED_TASKS = "scheduled_tasks"
        const val KEY_REMOTE_ENABLED = "remote_access_enabled"
        const val KEY_REMOTE_PORT = "remote_access_port"
        const val KEY_REMOTE_PIN = "remote_access_pin"
        const val KEY_REMOTE_VIEWER_URL = "remote_viewer_url"
        const val KEY_DESKTOP_NOTIFICATIONS = "desktop_notifications_enabled"
        const val KEY_GEN_PARAMS = "generation_params"
    }
}
