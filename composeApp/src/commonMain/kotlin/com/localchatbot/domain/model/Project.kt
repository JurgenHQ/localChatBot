package com.localchatbot.domain.model

import kotlinx.serialization.Serializable

/**
 * Proyecto: agrupación opcional de sesiones con su propia carpeta de workspace.
 * Solo tiene efecto en Desktop (las herramientas fs y el workspace son desktop-only);
 * en móvil no se muestra ni se usa.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val workspaceDir: String,
    /** Estado de la sección colapsable en el drawer (persistido). */
    val collapsed: Boolean = false,
    val createdAtEpochMs: Long
)

/**
 * Estado persistido de proyectos: la lista de proyectos más la membresía
 * sesión→proyecto ([assignments], `sessionId -> projectId`). Fuente de verdad de
 * [com.localchatbot.domain.repository.ProjectRepository]. Las asignaciones a
 * sesiones o proyectos inexistentes se tratan como "sin proyecto" al agrupar.
 */
@Serializable
data class ProjectState(
    val projects: List<Project> = emptyList(),
    val assignments: Map<String, String> = emptyMap()
)
