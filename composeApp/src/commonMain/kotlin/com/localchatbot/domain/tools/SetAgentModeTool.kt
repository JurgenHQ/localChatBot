package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.state.TurnSessionContext
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.model.AgentMode
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * Cambia el modo de agente (`Plan` ↔ `Build`) **de la sesión del turno en curso**.
 *
 * Existe porque el prompt de modo Plan le pide al modelo "presentá el plan y decile al usuario
 * que cambie a Build", pero el modelo no tenía con qué aplicarlo: preguntaba, el usuario decía
 * que sí, y ahí se acababa — había que ir al chip de la UI a mano.
 *
 * Escribe **solo el override de la sesión** (`sessionAgentModes`), nunca el `agentMode` global:
 * pasar a Build por una tarea concreta no debe cambiar el default de las demás conversaciones.
 *
 * **Pasar a Build siempre abre el diálogo de confirmación, incluso en YOLO** (`force = true`,
 * mismo criterio que la denylist de `run_command`): el modo Plan es una garantía de solo-lectura
 * y que el modelo se la quite sin que lo veas la anularía. Pasar a Plan no pide nada — solo
 * restringe. La excepción es un run automatizado ([com.localchatbot.core.confirm.AutoApproveConfirmations]),
 * que aprueba todo por definición: una tarea programada ya corre sin nadie delante y con
 * `run_command` auto-aprobado, así que bloquear justo esto sería incoherente.
 */
class SetAgentModeTool(
    private val prefs: PreferencesRepository,
    private val confirm: ToolConfirmationController,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME

    override val requiresConfirmation: Boolean = true

    override val activityLabel: String = "Cambiando modo del agente…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["mode"]?.jsonPrimitive?.content
    }.getOrNull()

    /** El modo Plan/Build solo gatea las tools de fs/shell, que son desktop-only. */
    override suspend fun isAvailable(): Boolean = PlatformCapabilities.isDesktop

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Switches the agent mode for the CURRENT conversation between " +
                "\"plan\" (read-only: file-mutating tools are disabled) and \"build\" (normal: " +
                "the agent can create and edit files). Call this with mode=\"build\" when you are " +
                "in Plan mode and the user has agreed to apply the plan — do NOT just tell them to " +
                "flip the switch themselves. Switching to \"build\" asks the user for confirmation " +
                "first. Once it succeeds the file-editing tools are available immediately, so you " +
                "can go straight on to applying the plan. Only affects this conversation, not the " +
                "global default.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("plan"))
                            add(JsonPrimitive("build"))
                        })
                        put("description", "\"build\" to enable file edits, \"plan\" to go back to read-only.")
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Short reason shown to the user in the confirmation dialog.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("mode")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        if (!PlatformCapabilities.isDesktop) {
            return FsToolUtil.errorPayload(json, "El modo Plan/Build solo aplica en desktop.")
        }

        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val requested = when (args["mode"]?.jsonPrimitive?.content?.trim()?.lowercase()) {
            "build" -> AgentMode.Build
            "plan" -> AgentMode.Plan
            else -> return FsToolUtil.errorPayload(json, "Argumento 'mode' debe ser \"plan\" o \"build\".")
        }

        // La sesión del turno, no la visible: el turno pudo lanzarse en background (tarea
        // programada, sub-agente) y el usuario estar mirando otra conversación.
        val sessionId = coroutineContext[TurnSessionContext]?.sessionId
            ?: return FsToolUtil.errorPayload(json, "No hay una sesión activa para cambiar de modo.")

        val current = prefs.current().let { it.sessionAgentModes[sessionId] ?: it.agentMode }
        if (current == requested) {
            return FsToolUtil.encode(
                json,
                buildJsonObject {
                    put("success", true)
                    put("mode", requested.name.lowercase())
                    put("changed", false)
                    put("note", "Already in ${requested.name} mode.")
                }
            )
        }

        // Solo se pide aprobación para ABRIR permisos (→ Build). Volver a Plan restringe, así
        // que un diálogo ahí sería ruido sin nada que proteger.
        if (requested == AgentMode.Build) {
            val reason = args["reason"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val approved = confirm.requestApproval(
                title = "Cambiar a modo Build",
                detail = reason?.let { "$it\n\nEl agente podrá crear y editar archivos en esta conversación." }
                    ?: "El agente podrá crear y editar archivos en esta conversación.",
                force = true
            )
            if (!approved) {
                return FsToolUtil.errorPayload(
                    json,
                    "El usuario rechazó el cambio a modo Build. Seguís en modo Plan: no intentes " +
                        "modificar archivos ni vuelvas a llamar a esta tool sin que te lo pida."
                )
            }
        }

        prefs.updateSessionAgentMode(sessionId, requested)

        return FsToolUtil.encode(
            json,
            buildJsonObject {
                put("success", true)
                put("mode", requested.name.lowercase())
                put("changed", true)
                put(
                    "note",
                    if (requested == AgentMode.Build) {
                        "Build mode enabled for this conversation. The file-mutating tools " +
                            "(create_file, edit_file, multi_edit, delete_file, create_directory) are " +
                            "available from your next round onwards — go ahead and apply the plan."
                    } else {
                        "Plan mode enabled for this conversation: the file-mutating tools are disabled."
                    }
                )
            }
        )
    }

    companion object {
        const val TOOL_NAME = "set_agent_mode"
    }
}
