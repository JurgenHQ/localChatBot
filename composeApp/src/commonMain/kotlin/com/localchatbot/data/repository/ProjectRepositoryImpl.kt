package com.localchatbot.data.repository

import com.localchatbot.core.util.newId
import com.localchatbot.domain.model.Project
import com.localchatbot.domain.model.ProjectState
import com.localchatbot.domain.repository.ProjectRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * [ProjectRepository] respaldado por `multiplatform-settings` (JSON en la key [KEY_STATE]).
 * No toca la base SQLite de sesiones: la membresía sesión→proyecto vive aquí como un mapa.
 * El [MutableStateFlow] es la fuente de verdad en memoria; cada mutación reserializa a disco.
 */
class ProjectRepositoryImpl(
    private val settings: Settings,
    private val json: Json
) : ProjectRepository {

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<ProjectState> = _state.asStateFlow()

    override suspend fun current(): ProjectState = _state.value

    override suspend fun createProject(name: String, workspaceDir: String): Project {
        val project = Project(
            id = newId(),
            name = name,
            workspaceDir = workspaceDir,
            createdAtEpochMs = Clock.System.now().toEpochMilliseconds()
        )
        update { it.copy(projects = it.projects + project) }
        return project
    }

    override suspend fun renameProject(id: String, name: String) = update { s ->
        s.copy(projects = s.projects.map { if (it.id == id) it.copy(name = name) else it })
    }

    override suspend fun updateWorkspace(id: String, workspaceDir: String) = update { s ->
        s.copy(projects = s.projects.map { if (it.id == id) it.copy(workspaceDir = workspaceDir) else it })
    }

    override suspend fun updateCollapsed(id: String, collapsed: Boolean) = update { s ->
        s.copy(projects = s.projects.map { if (it.id == id) it.copy(collapsed = collapsed) else it })
    }

    override suspend fun deleteProject(id: String) = update { s ->
        s.copy(
            projects = s.projects.filterNot { it.id == id },
            assignments = s.assignments.filterValues { it != id }
        )
    }

    override suspend fun assignSession(sessionId: String, projectId: String?) = update { s ->
        if (projectId == null) {
            s.copy(assignments = s.assignments - sessionId)
        } else {
            s.copy(assignments = s.assignments + (sessionId to projectId))
        }
    }

    override suspend fun detachSession(sessionId: String) = update { s ->
        s.copy(assignments = s.assignments - sessionId)
    }

    override suspend fun clearAssignments() = update { it.copy(assignments = emptyMap()) }

    private fun update(transform: (ProjectState) -> ProjectState) {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
    }

    private fun persist(state: ProjectState) {
        settings.putString(KEY_STATE, json.encodeToString(ProjectState.serializer(), state))
    }

    private fun load(): ProjectState = runCatching {
        val raw = settings.getStringOrNull(KEY_STATE) ?: return@runCatching ProjectState()
        json.decodeFromString(ProjectState.serializer(), raw)
    }.getOrDefault(ProjectState())

    private companion object {
        const val KEY_STATE = "projects_state"
    }
}
