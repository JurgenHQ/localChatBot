package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tools de git de primera clase, en vez de dejarlas caer en `run_command`.
 *
 * Tres razones para separarlas: `run_command` es confirmable siempre (revisar el estado del
 * repo no debería pedir permiso, y en modo Plan es una consulta perfectamente legítima), su
 * salida es texto sin estructura, y el modelo tiene que acertar la sintaxis exacta cada vez.
 * Aquí las de lectura no piden confirmación y están disponibles en Plan; solo `git_commit`,
 * que sí escribe, la pide.
 *
 * Por debajo siguen siendo `agent.runCommand` — no hay librería de git, y no hace falta.
 */
internal object GitSupport {

    /** Ejecuta un comando git en el workspace efectivo y devuelve el JSON de resultado. */
    suspend fun run(
        agent: FilesystemAgent,
        preferences: PreferencesRepository,
        json: Json,
        command: String,
        timeoutSeconds: Int = 30
    ): String {
        val workspace = FsToolUtil.effectiveWorkspace(preferences)
            ?: return FsToolUtil.errorPayload(json, "Sin workspace configurado")
        return FsToolUtil.fsResultToJson(
            json,
            agent.runCommand(command, workspace, timeoutSeconds, background = false)
        )
    }

    /** Igual que [run] pero devuelve el stdout crudo, para poder componer (p. ej. el diff del commit). */
    suspend fun stdout(
        agent: FilesystemAgent,
        preferences: PreferencesRepository,
        command: String,
        timeoutSeconds: Int = 30
    ): String? {
        val workspace = FsToolUtil.effectiveWorkspace(preferences) ?: return null
        val result = agent.runCommand(command, workspace, timeoutSeconds, background = false)
        return (result as? FsResult.Ok)?.payload?.get("stdout")?.jsonPrimitive?.content
    }

    /** `--` separa opciones de rutas: sin esto un fichero llamado `-x` se lee como flag. */
    fun pathArg(path: String?): String =
        if (path.isNullOrBlank()) "" else " -- ${quote(path)}"

    /**
     * Comillas para un argumento de shell.
     *
     * OJO con el alcance: esto vale para rutas, no para texto arbitrario. `runCommand` usa
     * el shell de login en macOS/Linux pero **cmd en Windows**, donde las comillas simples
     * son literales y este escape no sirve. Por eso el mensaje de commit —que lo escribe el
     * modelo y puede traer comillas, `$`, backticks o saltos de línea— NO pasa por aquí:
     * va por archivo con `git commit -F` (ver [GitCommitTool]), que no depende del shell.
     */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun argsOf(json: Json, argumentsJson: String): JsonObject? =
        runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
}

/** Estado del repo: rama, ficheros modificados/sin seguimiento, y si hay algo que commitear. */
class GitStatusTool(
    private val agent: FilesystemAgent,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {
    override val name: String = TOOL_NAME
    override val activityLabel: String = "Consultando estado de git…"
    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Show the git status of the workspace: current branch, staged, " +
                "modified and untracked files. Read-only, no confirmation needed. Prefer " +
                "this over running `git status` through run_command.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String =
        GitSupport.run(
            agent, preferences, json,
            "git rev-parse --abbrev-ref HEAD && echo '---' && git status --porcelain=v1 --branch"
        )

    companion object { const val TOOL_NAME = "git_status" }
}

/** Diff del working tree o del área de staging. */
class GitDiffTool(
    private val agent: FilesystemAgent,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {
    override val name: String = TOOL_NAME
    override val activityLabel: String = "Leyendo diff de git…"
    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Show the git diff of the workspace. By default shows unstaged " +
                "changes; set staged=true for what is already staged. Read-only. Use this " +
                "to review what you changed before committing, or to understand recent work.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional file or directory to limit the diff to.")
                    })
                    put("staged", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, diff the staging area against HEAD instead of the working tree.")
                    })
                    put("stat_only", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, return only the per-file summary (--stat), far cheaper for big diffs.")
                    })
                })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = GitSupport.argsOf(json, argumentsJson)
        val staged = args?.get("staged")?.jsonPrimitive?.booleanOrNull == true
        val statOnly = args?.get("stat_only")?.jsonPrimitive?.booleanOrNull == true
        val path = args?.get("path")?.jsonPrimitive?.content
        val flags = buildString {
            if (staged) append(" --cached")
            if (statOnly) append(" --stat")
        }
        return GitSupport.run(agent, preferences, json, "git diff$flags${GitSupport.pathArg(path)}")
    }

    companion object { const val TOOL_NAME = "git_diff" }
}

/** Historial reciente, en una línea por commit. */
class GitLogTool(
    private val agent: FilesystemAgent,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {
    override val name: String = TOOL_NAME
    override val activityLabel: String = "Leyendo historial de git…"
    override suspend fun isAvailable(): Boolean = FsToolUtil.isAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Show recent git commits (hash, author, date, subject), newest " +
                "first. Read-only. Useful to match the repository's commit message style " +
                "before writing one, or to see what changed recently in a file.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many commits to return. Default $DEFAULT_LIMIT, max $MAX_LIMIT.")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional file or directory to limit the history to.")
                    })
                })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = GitSupport.argsOf(json, argumentsJson)
        val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val path = args?.get("path")?.jsonPrimitive?.content
        return GitSupport.run(
            agent, preferences, json,
            "git log -n $limit --date=short --pretty=format:'%h|%an|%ad|%s'${GitSupport.pathArg(path)}"
        )
    }

    companion object {
        const val TOOL_NAME = "git_log"
        private const val DEFAULT_LIMIT = 15
        private const val MAX_LIMIT = 100
    }
}

/**
 * Crea un commit. Es la única tool git que escribe, así que es la única confirmable y la
 * única que exige modo Build.
 *
 * El diálogo enseña el `--stat` de lo que se va a incluir: aprobar un commit a ciegas,
 * sabiendo solo el mensaje, no es una aprobación informada.
 */
class GitCommitTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {
    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true
    override val activityLabel: String = "Creando commit…"

    override fun activityDetail(argumentsJson: String): String? =
        GitSupport.argsOf(json, argumentsJson)?.get("message")?.jsonPrimitive?.content
            ?.lineSequence()?.firstOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isWriteAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Create a git commit in the workspace. By default commits only " +
                "what is already staged; set add_all=true to stage every tracked " +
                "modification first. The user is asked to approve, and sees which files are " +
                "involved. Call git_log first if you want to match the repository's message style.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("message", buildJsonObject {
                        put("type", "string")
                        put("description", "Commit message. Multi-line is fine: first line is the subject.")
                    })
                    put("add_all", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "If true, stage all tracked modifications (git add -A) before committing. " +
                                "Untracked files are included too, so check git_status first."
                        )
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("message")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = GitSupport.argsOf(json, argumentsJson)
            ?: return FsToolUtil.errorPayload(json, "Arguments JSON inválido")
        val message = args["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return FsToolUtil.errorPayload(json, "Argumento 'message' faltante")
        val addAll = args["add_all"]?.jsonPrimitive?.booleanOrNull == true

        // Qué entraría en el commit, para que la aprobación sea informada y no un cheque en
        // blanco: con add_all lo que hay en el working tree, si no lo ya preparado.
        val preview = GitSupport.stdout(
            agent, preferences,
            if (addAll) "git diff --stat HEAD && git status --porcelain=v1" else "git diff --cached --stat"
        )?.trim().orEmpty()

        if (preview.isEmpty()) {
            return FsToolUtil.errorPayload(
                json,
                if (addAll) "No hay cambios que commitear en el workspace."
                else "No hay nada en el área de staging. Usa add_all=true o prepara los cambios primero."
            )
        }

        val approved = confirm.requestApproval(
            title = "Crear commit de git",
            detail = buildString {
                append(message.trim())
                append("\n\n")
                if (addAll) append("Se hará `git add -A` antes del commit.\n\n")
                append(preview.take(2_000))
            }
        )
        if (!approved) return FsToolUtil.cancelledPayload(json)

        val workspace = FsToolUtil.effectiveWorkspace(preferences)
            ?: return FsToolUtil.errorPayload(json, "Sin workspace configurado")

        // El `git add -A` va ANTES de escribir el archivo del mensaje, no encadenado con el
        // commit: si no, estaría preparando el propio archivo temporal y acabaría dentro del
        // commit. Al hacerlo primero, el archivo nace después del staging y queda sin seguir,
        // así que `git commit` (sin `-a`) no lo toca.
        if (addAll) {
            val staged = agent.runCommand("git add -A", workspace, 30, background = false)
            if (staged is FsResult.Err) {
                return FsToolUtil.errorPayload(json, "Falló `git add -A`: ${staged.message}")
            }
        }

        // El mensaje va por archivo (`git commit -F`) y no por `-m`: `runCommand` usa el
        // shell de login en macOS/Linux pero **cmd en Windows**, y no hay un escape de
        // comillas que valga para los dos. Con un archivo el mensaje no pasa por el shell,
        // así que comillas, `$`, backticks y saltos de línea llegan intactos en las tres
        // plataformas. Es además lo que hace git internamente (COMMIT_EDITMSG).
        val msgPath = "$workspace/$MESSAGE_FILE"
        when (val written = agent.createFile(msgPath, message.trim() + "\n", overwrite = true)) {
            is FsResult.Err -> return FsToolUtil.errorPayload(
                json, "No se pudo preparar el mensaje del commit: ${written.message}"
            )
            is FsResult.Ok -> Unit
        }

        val result = GitSupport.run(agent, preferences, json, "git commit -F ${GitSupport.quote(msgPath)}")
        // Se borra pase lo que pase con el commit: si no, quedaría suelto en el workspace y
        // el siguiente `git add -A` sí se lo llevaría.
        agent.deletePath(msgPath, recursive = false)
        return result
    }

    companion object {
        const val TOOL_NAME = "git_commit"

        /** Nombre con punto y sufijo propio: no colisiona y se reconoce si algo lo deja atrás. */
        private const val MESSAGE_FILE = ".localchatbot-commit-msg.tmp"
    }
}
