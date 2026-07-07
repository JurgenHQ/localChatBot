package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.storage.MemoryStore
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Guarda una preferencia del usuario en `memory.md`. El modelo la llama cuando el
 * usuario expresa una preferencia duradera ("recuerda que…", "siempre…", "no me gusta…").
 *
 * Requiere confirmación (salvo YOLO) para que el usuario vea exactamente qué se va a
 * recordar — la transparencia importa cuando el modelo persiste cosas sobre el usuario.
 * Solo desktop.
 */
class SaveMemoryTool(
    private val store: MemoryStore,
    private val confirm: ToolConfirmationController,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = true
    override val activityLabel: String = "Guardando preferencia…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["preference"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = store.isAvailable

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Saves a durable user preference to memory so you honor it in future " +
                "tasks. Call this whenever the user states a lasting preference (\"remember that…\", " +
                "\"always…\", \"I prefer…\", \"never…\") — about commits, naming, tone, language, " +
                "tooling, formatting, etc. Write ONE concise, self-contained preference per call, " +
                "in the user's language. Do NOT save one-off task details or transient context.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("preference", buildJsonObject {
                        put("type", "string")
                        put("description", "The preference to remember, as one concise sentence. " +
                            "E.g. \"Commits en inglés, sin firmas de IA\".")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("preference")) })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return errorPayload("Arguments JSON inválido: ${it.message}") }

        val preference = args["preference"]?.jsonPrimitive?.content?.trim()
        if (preference.isNullOrBlank()) {
            return errorPayload("Argumento 'preference' faltante o vacío")
        }

        val approved = confirm.requestApproval(
            title = "Recordar preferencia",
            detail = preference
        )
        if (!approved) return errorPayload("El usuario rechazó guardar la preferencia")

        return if (store.append(preference)) {
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("success", true)
                    put("saved", preference)
                }
            )
        } else {
            errorPayload("No se pudo escribir en memory.md")
        }
    }

    private fun errorPayload(message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", false)
                put("error", message)
            }
        )

    companion object {
        const val TOOL_NAME = "save_memory"
    }
}
