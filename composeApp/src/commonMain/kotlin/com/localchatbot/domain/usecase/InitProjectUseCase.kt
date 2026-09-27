package com.localchatbot.domain.usecase

import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.SafePathResult
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.state.ActiveWorkspaceStore
import com.localchatbot.domain.model.AgentMode
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Genera `AGENTS.md` (`/init`): explora el workspace efectivo con una sola pasada no
 * interactiva del modelo — sin loop de tools, igual de barato que [CompactContextUseCase] —
 * y propone el contenido en un diálogo editable antes de escribirlo.
 *
 * `AGENTS.md` es, a propósito, el nombre y no `CLAUDE.md`: es el primero que
 * `SendMessageUseCase.buildWorkspaceContext` ya busca al armar el bloque `<workspace>`, así
 * que el archivo generado se inyecta en el system prompt sin tocar nada más.
 *
 * Dos pasos, mismo espíritu que compact:
 * 1. [preview] junta contexto y le pide al modelo el borrador. No escribe nada.
 * 2. [apply] persiste el texto (posiblemente editado a mano). El click en "Aplicar" ya es
 *    la confirmación explícita — no hay `ToolConfirmationController` ni checkpoint de por
 *    medio, porque el archivo no existía antes (bloqueado si ya existe, ver [preview]) y no
 *    hay nada que revertir.
 */
class InitProjectUseCase(
    private val filesystemAgent: FilesystemAgent,
    private val activeWorkspaceStore: ActiveWorkspaceStore,
    private val model: ModelRepository,
    private val prefs: PreferencesRepository
) {

    suspend fun preview(): Result<String> {
        if (!PlatformCapabilities.isDesktop) {
            return Result.failure(IllegalStateException("Esta acción solo está disponible en Desktop"))
        }
        val ws = activeWorkspaceStore.current()
            ?: return Result.failure(IllegalStateException("No hay workspace configurado"))
        if (activeWorkspaceStore.currentAgentMode() != AgentMode.Build) {
            return Result.failure(IllegalStateException("Cambiá a modo Build para generar AGENTS.md"))
        }

        for (name in EXISTING_RULES_FILES) {
            val abs = (filesystemAgent.resolveSafePath(ws, name, allowOutside = false)
                as? SafePathResult.Ok)?.absPath ?: continue
            val exists = (filesystemAgent.readFileRaw(abs) as? FsResult.Ok)
                ?.payload?.get("content")?.jsonPrimitive?.content
                ?.isNotBlank() == true
            if (exists) {
                return Result.failure(
                    IllegalStateException("Ya existe $name en el workspace — edítalo a mano o borralo antes de correr /init")
                )
            }
        }

        val cfg = prefs.current().connection
        if (!cfg.isValid()) return Result.failure(IllegalStateException("Sin conexión configurada"))

        val context = buildProjectContext(ws)
        val content = model.generateDocument(cfg.baseUrl(), cfg.model, INIT_SYSTEM_PROMPT, context)
            ?: return Result.failure(IllegalStateException("El modelo no devolvió contenido"))

        return Result.success(content.trim())
    }

    suspend fun apply(content: String): Result<Unit> {
        val ws = activeWorkspaceStore.current()
            ?: return Result.failure(IllegalStateException("No hay workspace configurado"))
        val abs = (filesystemAgent.resolveSafePath(ws, "AGENTS.md", allowOutside = false) as? SafePathResult.Ok)?.absPath
            ?: return Result.failure(IllegalStateException("No se pudo resolver la ruta de AGENTS.md"))
        return when (val result = filesystemAgent.createFile(abs, content, overwrite = false)) {
            is FsResult.Ok -> Result.success(Unit)
            is FsResult.Err -> Result.failure(IllegalStateException(result.message))
        }
    }

    /**
     * Árbol root + manifiestos conocidos + README + `git log` reciente. Mismo espíritu que
     * `SendMessageUseCase.buildWorkspaceContext` pero sin el tope de una sola llamada por
     * turno: acá es la única llamada de todo el comando.
     */
    private suspend fun buildProjectContext(ws: String): String {
        val sb = StringBuilder()
        sb.append("Workspace: ").append(ws).append("\n\n")

        val tree = runCatching {
            (filesystemAgent.listDirectory(ws) as? FsResult.Ok)?.payload
                ?.get("entries")?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.sortedWith(
                    compareBy(
                        { (it["type"]?.jsonPrimitive?.content != "dir") },
                        { it["name"]?.jsonPrimitive?.content ?: "" }
                    )
                )
                ?.joinToString("\n") { e ->
                    val n = e["name"]?.jsonPrimitive?.content ?: ""
                    if (e["type"]?.jsonPrimitive?.content == "dir") "  $n/" else "  $n"
                }
        }.getOrNull()
        if (!tree.isNullOrBlank()) sb.append("Archivos (raíz):\n").append(tree).append("\n\n")

        for (name in MANIFEST_FILES) {
            val abs = (filesystemAgent.resolveSafePath(ws, name, allowOutside = false)
                as? SafePathResult.Ok)?.absPath ?: continue
            val content = (filesystemAgent.readFileRaw(abs) as? FsResult.Ok)
                ?.payload?.get("content")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: continue
            sb.append("--- $name ---\n").append(content.take(MANIFEST_CAP)).append("\n\n")
        }

        for (name in README_FILES) {
            val abs = (filesystemAgent.resolveSafePath(ws, name, allowOutside = false)
                as? SafePathResult.Ok)?.absPath ?: continue
            val content = (filesystemAgent.readFileRaw(abs) as? FsResult.Ok)
                ?.payload?.get("content")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
            if (content != null) {
                sb.append("--- $name ---\n").append(content.take(README_CAP)).append("\n\n")
                break
            }
        }

        val gitLog = runCatching {
            (filesystemAgent.runCommand(
                command = "git log --oneline -20 2>/dev/null",
                workingDir = ws,
                timeoutSeconds = 5
            ) as? FsResult.Ok)
                ?.payload?.get("stdout")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!gitLog.isNullOrBlank()) sb.append("git log (últimos 20):\n").append(gitLog).append("\n")

        return sb.toString()
    }

    private companion object {
        val EXISTING_RULES_FILES = listOf("AGENTS.md", "CLAUDE.md", ".cursorrules")
        val MANIFEST_FILES = listOf(
            "package.json", "build.gradle.kts", "build.gradle", "pyproject.toml",
            "Cargo.toml", "go.mod", "pom.xml", "Gemfile", "composer.json"
        )
        val README_FILES = listOf("README.md", "readme.md", "Readme.md")
        const val MANIFEST_CAP = 3_000
        const val README_CAP = 3_000

        const val INIT_SYSTEM_PROMPT =
            "You write AGENTS.md files: onboarding documentation for an AI coding agent working " +
                "in this repository. Given a snapshot of the project (file tree, manifest files, " +
                "README, recent git log), produce a complete AGENTS.md in Markdown covering: what " +
                "the project is, how to build/run/test it, the architecture and key directories, " +
                "and any non-obvious constraints a newcomer would miss. Be concrete and specific to " +
                "what you were given — never invent commands or files you didn't see. Write in the " +
                "same language as the README/comments if there's a clear one, otherwise English. " +
                "Respond with ONLY the Markdown content of the file, no preamble, no code fence."
    }
}
