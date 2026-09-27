package com.localchatbot.core.state

import com.localchatbot.domain.model.AgentMode
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.coroutineContext

/**
 * Resuelve el **contexto derivado de una sesión** que consumen las herramientas fs y el system
 * prompt: el workspace efectivo y el modo de agente efectivo.
 *
 * - **Workspace**: si la sesión está asignada a un proyecto existente, su carpeta;
 *   si no (sin proyecto, o asignación huérfana), el `fsWorkspaceDir` global.
 * - **Modo agente**: si la sesión tiene un override en `sessionAgentModes`, ese;
 *   si no, el `agentMode` global (valor por defecto).
 *
 * Así, sin proyectos ni overrides, el comportamiento es idéntico al anterior.
 *
 * **Hay dos "sesiones" distintas y no siempre coinciden**, de ahí que existan dos vías:
 * - Los [StateFlow] ([effectiveWorkspace], [effectiveAgentMode]) miran la sesión **visible**
 *   ([ActiveSessionStore]) y son para la UI (el chip del workspace, el toggle Plan/Build).
 * - [current] y [currentAgentMode] miran la sesión del **turno en curso**
 *   ([TurnSessionContext]) y son para las tools y el system prompt. Un turno corre en
 *   `applicationScope` y sobrevive a que el usuario cambie de conversación — y una tarea
 *   programada corre en paralelo al chat interactivo —, así que resolver el workspace o el
 *   modo Plan/Build contra la sesión visible dejaba que un turno escribiera en el workspace
 *   de otra conversación o quedara gateado por el modo de otra.
 */
class ActiveWorkspaceStore(
    private val activeSessionStore: ActiveSessionStore,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: PreferencesRepository,
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

    /**
     * Workspace efectivo de la sesión del turno en curso (para lecturas puntuales desde
     * tools/usecases). Ver la nota de la clase sobre por qué NO lee [effectiveWorkspace].
     */
    suspend fun current(): String? = forSession(turnSessionId())

    /**
     * Workspace efectivo de una sesión **cualquiera**, sin pasar por la sesión visible ni por
     * la del turno. Lo necesita la terminal integrada, que es por sesión y tiene que arrancar
     * su shell en la carpeta de esa conversación aunque estés mirando otra.
     */
    suspend fun forSession(sessionId: String?): String? {
        val projectState = projectRepository.current()
        val projectId = sessionId?.let { projectState.assignments[it] }
        val project = projectId?.let { pid -> projectState.projects.firstOrNull { it.id == pid } }
        return project?.workspaceDir ?: preferencesRepository.current().fsWorkspaceDir
    }

    /** Modo de agente efectivo del turno en curso (gating de tools de escritura y system prompt). */
    suspend fun currentAgentMode(): AgentMode {
        val sessionId = turnSessionId()
        val prefs = preferencesRepository.current()
        return sessionId?.let { prefs.sessionAgentModes[it] } ?: prefs.agentMode
    }

    /**
     * Sesión del turno en curso; si se llama fuera de un turno (UI, tests) cae a la visible,
     * que es el comportamiento que tenía todo antes de [TurnSessionContext].
     */
    private suspend fun turnSessionId(): String? =
        coroutineContext[TurnSessionContext]?.workspaceSessionId
            ?: activeSessionStore.activeSessionId.value
}
