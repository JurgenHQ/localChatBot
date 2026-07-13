package com.localchatbot.core.state

import com.localchatbot.domain.model.AgentMode
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Resuelve el **contexto derivado de la sesión activa** que consumen las herramientas fs y
 * el system prompt: el workspace efectivo y el modo de agente efectivo.
 *
 * - **Workspace**: si la sesión activa está asignada a un proyecto existente, su carpeta;
 *   si no (sin proyecto, o asignación huérfana), el `fsWorkspaceDir` global.
 * - **Modo agente**: si la sesión activa tiene un override en `sessionAgentModes`, ese;
 *   si no, el `agentMode` global (valor por defecto).
 *
 * Así, sin proyectos ni overrides, el comportamiento es idéntico al anterior. Solo hay una
 * sesión enviando a la vez, por lo que un único "contexto activo" es coherente con el pipeline.
 */
class ActiveWorkspaceStore(
    activeSessionStore: ActiveSessionStore,
    projectRepository: ProjectRepository,
    preferencesRepository: PreferencesRepository,
    scope: CoroutineScope
) {
    val effectiveWorkspace: StateFlow<String?> = combine(
        activeSessionStore.activeSessionId,
        projectRepository.state,
        preferencesRepository.preferences
    ) { activeId, projectState, prefs ->
        val projectId = activeId?.let { projectState.assignments[it] }
        val project = projectId?.let { pid -> projectState.projects.firstOrNull { it.id == pid } }
        project?.workspaceDir ?: prefs.fsWorkspaceDir
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val effectiveAgentMode: StateFlow<AgentMode> = combine(
        activeSessionStore.activeSessionId,
        preferencesRepository.preferences
    ) { activeId, prefs ->
        activeId?.let { prefs.sessionAgentModes[it] } ?: prefs.agentMode
    }.stateIn(scope, SharingStarted.Eagerly, AgentMode.Build)

    /** Workspace efectivo actual (para lecturas puntuales desde tools/usecases). */
    fun current(): String? = effectiveWorkspace.value

    /** Modo de agente efectivo actual (para gating de tools de escritura y el system prompt). */
    fun currentAgentMode(): AgentMode = effectiveAgentMode.value
}
