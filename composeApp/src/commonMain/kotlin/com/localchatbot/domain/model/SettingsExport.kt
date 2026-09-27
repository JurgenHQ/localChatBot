package com.localchatbot.domain.model

import com.localchatbot.core.theme.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
data class SettingsExport(
    val version: Int = 1,                 // versión de esquema → migración futura
    val connectionProfiles: List<ConnectionProfile> = emptyList(),
    val activeConnectionProfileId: String = "",
    /** Deprecado: solo para leer backups anteriores a los perfiles de conexión. */
    val connection: ConnectionConfig? = null,
    val themeMode: ThemeMode,
    val accentSeed: Long,
    val tavilyApiKey: String,
    val defaultSystemPrompt: String,
    val promptTemplates: List<PromptTemplate>,
    val imageServiceUrl: String,
    /** Con default: los backups anteriores al índice semántico siguen deserializando. */
    val embeddingsModel: String = "",
    val fsWorkspaceDir: String?,
    val fsYoloMode: Boolean,
    val fsAllowOutsideWorkspace: Boolean,
    val installedSkills: List<InstalledSkill>,
    val customSkills: List<SkillDefinition>,
    val mcpServers: List<McpServerConfig>,
    val scheduledTasks: List<ScheduledTask> = emptyList()
)
