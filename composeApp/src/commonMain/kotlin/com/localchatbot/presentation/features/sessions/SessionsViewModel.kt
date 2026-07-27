package com.localchatbot.presentation.features.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.QueuedMessageStore
import com.localchatbot.core.storage.CheckpointStore
import com.localchatbot.domain.model.SessionSummary
import com.localchatbot.domain.model.ConnectionProfile
import com.localchatbot.domain.model.Project
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import com.localchatbot.domain.search.MIN_SEARCH_QUERY_LENGTH
import com.localchatbot.domain.search.MessageSearchResult
import com.localchatbot.domain.usecase.CreateSessionUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val sessions: List<SessionSummary>
)

data class SessionsUiState(
    /** Sesiones sin proyecto (o con asignación huérfana), ya filtradas por [query]. */
    val ungrouped: List<SessionSummary> = emptyList(),
    /** Grupos por proyecto (solo en Desktop). Incluye proyectos vacíos. */
    val groups: List<ProjectGroup> = emptyList(),
    /** Sesiones creadas por tareas automatizadas (sección especial "Tareas automatizadas"). */
    val automationSessions: List<SessionSummary> = emptyList(),
    /** Copias guardadas al reenviar un mensaje antiguo (sección "Ramas anteriores"). */
    val branchSessions: List<SessionSummary> = emptyList(),
    /** Sesiones abiertas por la tool `spawn_agent` (sección "Sub-agentes"). */
    val subAgentSessions: List<SessionSummary> = emptyList(),
    val query: String = "",
    /**
     * Coincidencias dentro del **contenido** de los mensajes (índice FTS5), a diferencia del
     * resto de listas de este estado, que filtran por título. Vacía mientras la query no
     * llegue a [MIN_SEARCH_QUERY_LENGTH].
     */
    val messageResults: List<MessageSearchResult> = emptyList(),
    val searchingMessages: Boolean = false,
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
    private val checkpointStore: CheckpointStore? = null,
    private val queuedMessageStore: QueuedMessageStore? = null
) : ViewModel() {

    private val _local = MutableStateFlow(LocalState())
    private val _search = MutableStateFlow(SearchState())
    private var searchJob: Job? = null

    val state: StateFlow<SessionsUiState> = combine(
        chatRepository.sessionSummaries,
        preferences.preferences,
        projectRepository.state,
        _local,
        _search
    ) { sessions, prefs, projectState, local, search ->
        val projectsEnabled = PlatformCapabilities.isDesktop
        fun matches(s: SessionSummary) = local.query.isBlank() || s.title.contains(local.query, ignoreCase = true)

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
                messageResults = search.results,
                searchingMessages = search.running,
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
        val branchesId = ProjectRepository.BRANCHES_GROUP_ID
        val subAgentsId = ProjectRepository.SUBAGENTS_GROUP_ID
        val grouped = sessions.groupBy { s ->
            val pid = projectState.assignments[s.id]
            when {
                pid == autoId -> autoId
                pid == branchesId -> branchesId
                pid == subAgentsId -> subAgentsId
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
        val branchSessions = grouped[branchesId].orEmpty().filter(::matches)
        val subAgentSessions = grouped[subAgentsId].orEmpty().filter(::matches)

        SessionsUiState(
            ungrouped = ungrouped,
            groups = groups,
            automationSessions = automationSessions,
            branchSessions = branchSessions,
            subAgentSessions = subAgentSessions,
            query = local.query,
            messageResults = search.results,
            searchingMessages = search.running,
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
    /**
     * Filtrar por título es instantáneo (ya está todo en memoria) y se aplica en el acto; la
     * búsqueda en el contenido va a SQLite, así que se espera [SEARCH_DEBOUNCE_MS] a que el
     * usuario deje de teclear — escribir "configuración" lanzaría 13 consultas.
     */
    fun onQueryChange(value: String) {
        _local.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.trim().length < MIN_SEARCH_QUERY_LENGTH) {
            _search.value = SearchState()
            return
        }
        _search.update { it.copy(running = true) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val results = chatRepository.searchMessages(value)
            _search.value = SearchState(results = results, running = false)
        }
    }

    /**
     * Abre la conversación del resultado y deja pedido el scroll hasta el mensaje. No limpia
     * la búsqueda: lo normal al buscar es ir mirando varias coincidencias, y perder la lista
     * en el primer clic obligaría a reescribir la query cada vez.
     */
    fun selectSearchResult(result: MessageSearchResult) {
        activeSessionStore.selectAndScrollTo(result.sessionId, result.messageId)
        closeDrawer()
    }

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
            // Sin esto la cola de una sesión borrada quedaría colgada en memoria.
            queuedMessageStore?.clear(id)
            checkpointStore?.deleteSession(id)
            projectRepository.detachSession(id)
            // Sin esto el corte de compactación quedaría huérfano en preferencias para
            // siempre (la sesión ya no existe y nadie más lo limpia).
            preferences.updateSessionCompactBoundary(id, null)
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

    private fun allSessions(): List<SessionSummary> =
        state.value.ungrouped + state.value.groups.flatMap { it.sessions }

    private data class LocalState(
        val query: String = "",
        val drawerOpen: Boolean = false
    )

    /** Aparte de [LocalState] porque se resuelve de forma asíncrona y con retardo. */
    private data class SearchState(
        val results: List<MessageSearchResult> = emptyList(),
        val running: Boolean = false
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
