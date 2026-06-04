package com.localchatbot.domain.repository

import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.PromptTemplate
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
    suspend fun reset()
}
