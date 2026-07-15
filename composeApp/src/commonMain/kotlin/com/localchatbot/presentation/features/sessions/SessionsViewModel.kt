package com.localchatbot.presentation.features.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.storage.CheckpointStore
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.ConnectionProfile
import com.localchatbot.domain.model.Project
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un proyecto con las sesiones que tiene asignadas (ya filtradas por la búsqueda). */
data class ProjectGroup(
    val project: Project,
    val sessions: List<ChatSession>
)

data class SessionsUiState(
    /** Sesiones sin proyecto (o con asignación huérfana), ya filtradas por [query]. */
    val ungrouped: List<ChatSession> = emptyList(),
    /** Grupos por proyecto (solo en Desktop). Incluye proyectos vacíos. */
    val groups: List<ProjectGroup> = emptyList(),
    /** Sesiones creadas por tareas automatizadas (sección especial "Tareas automatizadas"). */
    val automationSessions: List<ChatSession> = emptyList(),
    val query: String = "",
    val drawerOpen: Boolean = false,
    val connectionLabel: String = "",
    /** True en Desktop: habilita la UI de proyectos (secciones, crear, mover…). */
    val projectsEnabled: Boolean = false,
    /** Perfiles de conexión disponibles (máx. 3), para el switcher de la cabecera del drawer. */
    val connectionProfiles: List<ConnectionProfile> = emptyList(),
    val activeConnectionProfileId: String = ""
)

class SessionsViewModel(
    private val chatRepository: ChatRepository,
    private val preferences: PreferencesRepository,
    private val activeSessionStore: ActiveSessionStore,
    private val createSessionUseCase: CreateSessionUseCase,
    private val projectRepository: ProjectRepository,
    private val checkpointStore: CheckpointStore? = null
) : ViewModel() {

    private val _local = MutableStateFlow(LocalState())

    val state: StateFlow<SessionsUiState> = combine(
        chatRepository.sessions,
        preferences.preferences,
        projectRepository.state,
        _local
    ) { sessions, prefs, projectState, local ->
        val projectsEnabled = PlatformCapabilities.isDesktop
        fun matches(s: ChatSession) = local.query.isBlank() || s.title.contains(local.query, ignoreCase = true)

        val connectionLabel = when {
            !prefs.connection.isValid() -> prefs.connection.model
            prefs.connection.port.isBlank() -> prefs.connection.ip.take(32)
            else -> "${prefs.connection.ip}:${prefs.connection.port}"
        }

        if (!projectsEnabled) {
            // Móvil: lista plana idéntica al comportamiento anterior (sin secciones).
            return@combine SessionsUiState(
                ungrouped = sessions.filter(::matches),
                query = local.query,
                drawerOpen = local.drawerOpen,
                connectionLabel = connectionLabel,
                projectsEnabled = false,
                connectionProfiles = prefs.connectionProfiles,
                activeConnectionProfileId = prefs.activeConnectionProfileId
            )
        }

        val projectsById = projectState.projects.associateBy { it.id }
        // Clave de grupo por sesión: el grupo reservado de tareas, un proyecto existente, o
        // null ("sin proyecto"). Una asignación a un proyecto inexistente cae a "sin proyecto".
        val autoId = ProjectRepository.AUTOMATION_GROUP_ID
        val grouped = sessions.groupBy { s ->
            val pid = projectState.assignments[s.id]
            when {
                pid == autoId -> autoId
                pid != null && projectsById.containsKey(pid) -> pid
                else -> null
            }
        }

        val groups = projectState.projects
            .sortedBy { it.createdAtEpochMs }
            .map { project ->
                ProjectGroup(project, grouped[project.id].orEmpty().filter(::matches))
            }
        val ungrouped = grouped[null].orEmpty().filter(::matches)
        val automationSessions = grouped[autoId].orEmpty().filter(::matches)

        SessionsUiState(
            ungrouped = ungrouped,
            groups = groups,
            automationSessions = automationSessions,
            query = local.query,
            drawerOpen = local.drawerOpen,
            connectionLabel = connectionLabel,
            projectsEnabled = projectsEnabled,
            connectionProfiles = prefs.connectionProfiles,
            activeConnectionProfileId = prefs.activeConnectionProfileId
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    /** Cambia el perfil de conexión activo desde el switcher de la cabecera del drawer. */
    fun switchConnectionProfile(id: String) {
        viewModelScope.launch { preferences.setActiveConnectionProfile(id) }
    }

    fun openDrawer() = _local.update { it.copy(drawerOpen = true) }
    fun closeDrawer() = _local.update { it.copy(drawerOpen = false) }
    fun onQueryChange(value: String) = _local.update { it.copy(query = value) }

    fun selectSession(id: String) {
        activeSessionStore.set(id)
        closeDrawer()
    }

    fun newSession() = newSessionInProject(null)

    /** Crea una sesión y, si [projectId] no es null, la asigna a ese proyecto. */
    fun newSessionInProject(projectId: String?) {
        viewModelScope.launch {
            val session = createSessionUseCase()
            if (projectId != null) projectRepository.assignSession(session.id, projectId)
            activeSessionStore.set(session.id)
            closeDrawer()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            activeSessionStore.clearIfMatches(id)
            checkpointStore?.deleteSession(id)
            projectRepository.detachSession(id)
        }
    }

    fun renameSession(id: String, newTitle: String) {
        val cleaned = newTitle.trim().ifBlank { return }
        viewModelScope.launch { chatRepository.updateTitle(id, cleaned) }
    }

    fun togglePinned(id: String) {
        val session = allSessions().firstOrNull { it.id == id } ?: return
        viewModelScope.launch { chatRepository.setPinned(id, !session.pinned) }
    }

    // ---- Proyectos (Desktop) ----

    fun createProject(name: String, workspaceDir: String) {
        val cleaned = name.trim().ifBlank { return }
        viewModelScope.launch { projectRepository.createProject(cleaned, workspaceDir) }
    }

    fun renameProject(id: String, name: String) {
        val cleaned = name.trim().ifBlank { return }
        viewModelScope.launch { projectRepository.renameProject(id, cleaned) }
    }

    fun updateProjectWorkspace(id: String, workspaceDir: String) {
        viewModelScope.launch { projectRepository.updateWorkspace(id, workspaceDir) }
    }

    fun toggleProjectCollapsed(id: String) {
        val project = state.value.groups.firstOrNull { it.project.id == id }?.project ?: return
        viewModelScope.launch { projectRepository.updateCollapsed(id, !project.collapsed) }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { projectRepository.deleteProject(id) }
    }

    fun moveSessionToProject(sessionId: String, projectId: String?) {
        viewModelScope.launch { projectRepository.assignSession(sessionId, projectId) }
    }

    private fun allSessions(): List<ChatSession> =
        state.value.ungrouped + state.value.groups.flatMap { it.sessions }

    private data class LocalState(
        val query: String = "",
        val drawerOpen: Boolean = false
    )
}
