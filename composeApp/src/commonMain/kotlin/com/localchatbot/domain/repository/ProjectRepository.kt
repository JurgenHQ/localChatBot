package com.localchatbot.domain.repository

import com.localchatbot.domain.model.Project
import com.localchatbot.domain.model.ProjectState
import kotlinx.coroutines.flow.Flow

/**
 * Proyectos y membresía sesión→proyecto. Desktop-only en la práctica (el workspace por
 * proyecto solo aplica en Desktop), pero la interfaz vive en commonMain porque la resuelve
 * [com.localchatbot.core.state.ActiveWorkspaceStore] y la consume la UI compartida.
 *
 * Persistido como JSON en settings; no toca la base SQLite de sesiones.
 */
interface ProjectRepository {
    val state: Flow<ProjectState>
    suspend fun current(): ProjectState

    suspend fun createProject(name: String, workspaceDir: String): Project
    suspend fun renameProject(id: String, name: String)
    suspend fun updateWorkspace(id: String, workspaceDir: String)
    suspend fun updateCollapsed(id: String, collapsed: Boolean)
    /** Borra el proyecto; sus sesiones quedan sin proyecto (no se borran). */
    suspend fun deleteProject(id: String)

    /** Asigna la sesión a un proyecto, o la quita si [projectId] es null. */
    suspend fun assignSession(sessionId: String, projectId: String?)
    /** Quita la sesión de cualquier proyecto (usado al borrar la sesión). */
    suspend fun detachSession(sessionId: String)
    /** Limpia toda la membresía (usado por clearAll). Los proyectos se conservan. */
    suspend fun clearAssignments()

    companion object {
        /**
         * Id de grupo reservado (no es un [Project] real) al que el scheduler asigna las
         * sesiones que crea cada tarea automatizada. La UI lo agrupa en una sección especial
         * "Tareas automatizadas"; como no está en la lista de proyectos, la resolución de
         * workspace cae al global igual que una sesión sin proyecto.
         */
        const val AUTOMATION_GROUP_ID = "__automated_tasks__"
    }
}
