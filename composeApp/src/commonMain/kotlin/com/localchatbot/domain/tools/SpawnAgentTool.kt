package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.AutoApproveConfirmations
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ProjectRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * Marcador de contexto de corutina que indica "esta corutina ES un sub-agente".
 *
 * Se propaga por el árbol de corutinas del turno hijo igual que [AutoApproveConfirmations],
 * y [SpawnAgentTool.isAvailable] lo lee para **no ofrecerse al modelo** dentro de un hijo:
 * anidamiento máximo 1, un sub-agente no puede lanzar nietos. Sin este tope, una instrucción
 * ambigua puede abrir un árbol de sesiones sin fondo, cada una consumiendo el modelo local
 * en serie.
 */
class SubAgentRun : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SubAgentRun>
}

/**
 * Lanza un **sub-agente**: abre una sesión de chat hija, le da una única instrucción, corre
 * el loop de tools completo y devuelve al padre **solo el texto final** del hijo.
 *
 * El punto de la tool es el contexto: una subtarea larga (explorar un repo, probar tres
 * enfoques, leer diez archivos) quema la ventana del padre con salidas intermedias que ya
 * no aportan nada una vez sacada la conclusión. El hijo se las come en su propia ventana.
 *
 * Decisiones de diseño:
 * - **El hijo arranca limpio**: recibe la instrucción y nada del historial del padre. Eso es
 *   justamente lo que ahorra contexto, así que la instrucción tiene que ser autocontenida
 *   (lo dice la `description` que ve el modelo).
 * - **La sesión hija es visible** en el drawer, bajo [ProjectRepository.SUBAGENTS_GROUP_ID].
 *   Un sub-agente ejecuta tools reales sobre el workspace; esconder lo que hizo impediría
 *   auditarlo.
 * - **Hereda auto-aprobación** ([AutoApproveConfirmations]), igual que las tareas
 *   programadas: nadie está mirando la sesión hija para aprobar diálogos, y el padre está
 *   bloqueado esperándola.
 * - **Anidamiento máximo 1**, vía [SubAgentRun] (ver arriba).
 *
 * Nota: el hijo comparte con el padre el workspace efectivo (lo resuelve
 * [com.localchatbot.core.state.ActiveWorkspaceStore] por la sesión *activa*, que sigue
 * siendo la del padre) y la lista de todos de [TodoTool] (también indexada por sesión
 * activa). Es el mismo comportamiento que ya tienen las sesiones del scheduler.
 */
class SpawnAgentTool(
    private val chats: ChatRepository,
    private val projects: ProjectRepository,
    private val createSession: CreateSessionUseCase,
    /**
     * Provider perezoso de [SendMessageUseCase]. No puede ser una dependencia directa:
     * el use case necesita el [ToolRegistry] que contiene esta tool, así que inyectarlo
     * por constructor sería una dependencia circular. El DI pasa `{ sendMessage }`, que
     * se resuelve en la primera ejecución, cuando ya está construido.
     */
    private val sendMessageProvider: () -> SendMessageUseCase,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    /** El hijo ya auto-aprueba sus propias tools; pedir confirmación aquí no aporta. */
    override val requiresConfirmation: Boolean = false

    override val activityLabel: String = "Sub-agente trabajando…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        val args = json.parseToJsonElement(argumentsJson).jsonObject
        args["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: args["task"]?.jsonPrimitive?.content?.take(80)
    }.getOrNull()

    /**
     * Desktop-only (el valor de la tool está en las tools locales que corre el hijo) y
     * **nunca dentro de otro sub-agente**: si el marcador está presente, la definición ni
     * se envía al modelo, así que físicamente no puede anidar.
     */
    override suspend fun isAvailable(): Boolean =
        PlatformCapabilities.isDesktop && coroutineContext[SubAgentRun] == null

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Runs a self-contained subtask in a separate agent with its own fresh " +
                "context, and returns ONLY its final answer. Use it when a subtask would flood " +
                "this conversation with intermediate output you won't need afterwards — " +
                "exploring an unfamiliar part of the codebase, reading many files to answer one " +
                "question, trying an approach you may discard. " +
                "The sub-agent inherits NOTHING from this conversation: `task` must be " +
                "completely self-contained (state the goal, the relevant paths, and what to " +
                "report back). It works on the same workspace with the same tools, its file " +
                "changes are real, and its confirmations are auto-approved — so don't delegate " +
                "anything you wouldn't approve yourself. It cannot spawn further sub-agents. " +
                "Don't use it for a single file read or a one-line question: it costs a whole " +
                "extra model run.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Self-contained instructions for the sub-agent, including what to " +
                                "report back. It sees nothing of this conversation."
                        )
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Short label for the child session in the sidebar (a few words).")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("task")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        // Defensa en profundidad: la definición no se envía dentro de un hijo, pero un
        // modelo puede inventarse la llamada aun sin tenerla en la lista.
        if (coroutineContext[SubAgentRun] != null) {
            return FsToolUtil.errorPayload(
                json,
                "Un sub-agente no puede lanzar otros sub-agentes. Resolvé la tarea vos mismo."
            )
        }
        if (!PlatformCapabilities.isDesktop) {
            return FsToolUtil.errorPayload(json, "spawn_agent solo está disponible en desktop")
        }

        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val task = args["task"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return FsToolUtil.errorPayload(json, "Argumento 'task' faltante")
        val title = args["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: task.lineSequence().first().take(40)

        val session = runCatching { createSession() }
            .getOrElse { return FsToolUtil.errorPayload(json, "No se pudo crear la sesión hija: ${it.message}") }

        runCatching {
            chats.updateTitle(session.id, "$SUBAGENT_TITLE_PREFIX $title")
            // Sección "Sub-agentes" del drawer: visible, para poder auditar lo que hizo.
            projects.assignSession(session.id, ProjectRepository.SUBAGENTS_GROUP_ID)
        }

        val result = withContext(AutoApproveConfirmations() + SubAgentRun()) {
            sendMessageProvider().invoke(session.id, task)
        }

        val error = result.exceptionOrNull()
        if (error != null) {
            return FsToolUtil.encode(
                json,
                buildJsonObject {
                    put("success", false)
                    put("session_id", session.id)
                    put("error", "El sub-agente falló: ${error.message ?: "error desconocido"}")
                }
            )
        }

        val answer = finalAnswer(session.id)
            ?: return FsToolUtil.encode(
                json,
                buildJsonObject {
                    put("success", false)
                    put("session_id", session.id)
                    put("error", "El sub-agente terminó sin producir texto final.")
                }
            )

        return FsToolUtil.encode(
            json,
            buildJsonObject {
                put("success", true)
                put("session_id", session.id)
                put("result", answer)
            }
        )
    }

    /**
     * Último mensaje de assistant con texto de la sesión hija. Solo eso vuelve al padre:
     * su historial completo (tool calls, archivos leídos, salidas de comandos) se queda en
     * la sesión hija, que es exactamente el ahorro de contexto que justifica la tool.
     */
    private suspend fun finalAnswer(sessionId: String): String? {
        val child = chats.getSession(sessionId) ?: return null
        val text = child.messages
            .lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (text.length > MAX_RESULT_CHARS) {
            text.take(MAX_RESULT_CHARS) + "\n[… respuesta del sub-agente truncada …]"
        } else {
            text
        }
    }

    companion object {
        const val TOOL_NAME = "spawn_agent"

        /** Prefijo del título de las sesiones hijas, para reconocerlas de un vistazo. */
        const val SUBAGENT_TITLE_PREFIX = "🤖"

        /**
         * Tope del texto devuelto al padre. El truncado genérico de [truncateToolOutput]
         * (8k, cabeza+cola) partiría el JSON por la mitad; recortando acá el payload sigue
         * siendo JSON válido y el corte queda al final del texto, donde suele estar la
         * conclusión menos densa.
         */
        private const val MAX_RESULT_CHARS = 6_000
    }
}
