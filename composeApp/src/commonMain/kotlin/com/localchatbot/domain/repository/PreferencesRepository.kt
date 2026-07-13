package com.localchatbot.domain.repository

import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.AgentMode
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.InstalledSkill
import com.localchatbot.domain.model.McpServerConfig
import com.localchatbot.domain.model.PromptTemplate
import com.localchatbot.domain.model.SkillDefinition
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>
    suspend fun current(): AppPreferences
    suspend fun updateConnection(config: ConnectionConfig)
    suspend fun updateThemeMode(mode: ThemeMode)
    suspend fun updateAccent(seed: Long)
    suspend fun markOnboardingDone()
    suspend fun updateTavilyApiKey(value: String)
    suspend fun updateDefaultSystemPrompt(value: String)
    suspend fun setPromptTemplates(templates: List<PromptTemplate>)
    suspend fun updateImageServiceUrl(value: String)
    suspend fun updateFsWorkspaceDir(value: String?)
    suspend fun updateFsYoloMode(value: Boolean)
    suspend fun updateFsAllowOutsideWorkspace(value: Boolean)
    suspend fun updateFsPreviewEdits(value: Boolean)
    suspend fun updateAgentMode(value: AgentMode)
    /** Fija el modo del agente para una sesión concreta (override de [updateAgentMode]). */
    suspend fun updateSessionAgentMode(sessionId: String, value: AgentMode)
    /** Quita todos los overrides de modo por sesión (usado por clearAll). */
    suspend fun clearSessionAgentModes()
    suspend fun setInstalledSkills(skills: List<InstalledSkill>)
    suspend fun setCustomSkills(skills: List<SkillDefinition>)
    suspend fun refreshCustomSkills()
    suspend fun setMcpServers(servers: List<McpServerConfig>)
    suspend fun setScheduledTasks(tasks: List<com.localchatbot.domain.model.ScheduledTask>)
    suspend fun updateRemoteAccess(enabled: Boolean, port: Int, pin: String)
    suspend fun updateRemoteViewerUrl(value: String)
    suspend fun updateDesktopNotifications(value: Boolean)
    suspend fun updateGenerationParams(params: GenerationParams)
    suspend fun reset()
    suspend fun exportJson(): String
    suspend fun importJson(json: String)   // lanza excepción si el JSON es inválido
}
