package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.MultiFileEdit
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Aplica múltiples reemplazos a un archivo de forma atómica: todos deben
 * resolverse o ninguno se escribe. Ideal para cambios que se distribuyen por
 * varias partes del archivo y cuya semántica depende de aplicarse juntos.
 */
class MultiEditTool(
    private val agent: FilesystemAgent,
    private val confirm: ToolConfirmationController?,
    private val preferences: PreferencesRepository,
    private val json: Json
) : Tool {

    override val name: String = TOOL_NAME
    override val requiresConfirmation: Boolean = false
    override val activityLabel: String = "Editando archivo (multi_edit)…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isWriteAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Apply multiple string-replace edits to a file atomically: ALL edits " +
                "are validated in memory first, and the file is only written if EVERY edit " +
                "succeeds. Edits are applied sequentially — each one sees the result of the " +
                "previous. Use this when several independent hunks need to change together " +
                "(e.g. rename a symbol across its declaration and all usages). Prefer this " +
                "over multiple separate `edit_file` calls when the changes are logically atomic.\n" +
                "Rules: same as `edit_file` MODE A — `old_string` must be unique in the file " +
                "at the moment it is applied (use surrounding context to disambiguate); " +
                "`old_string` ≠ `new_string`.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path. Relative paths resolve against the workspace.")
                    })
                    put("edits", buildJsonObject {
                        put("type", "array")
                        put("description", "Ordered list of string-replace edits to apply atomically.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("old_string", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Exact text to replace. Must be unique at the point of application.")
                                })
                                put("new_string", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Replacement text.")
                                })
                                put("replace_all", buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "If true, replace every occurrence of old_string. Defaults to false.")
                                })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("old_string"))
                                add(JsonPrimitive("new_string"))
                            })
                            put("additionalProperties", false)
                        })
                        put("minItems", 1)
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("edits"))
                })
                put("additionalProperties", false)
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return FsToolUtil.errorPayload(json, "Arguments JSON inválido: ${it.message}") }

        val path = args["path"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'path' faltante")

        val editsArr = args["edits"]?.jsonArray
            ?: return FsToolUtil.errorPayload(json, "Argumento 'edits' faltante o no es un array")

        if (editsArr.isEmpty()) {
            return FsToolUtil.errorPayload(json, "'edits' no puede estar vacío")
        }

        val edits = runCatching {
            editsArr.map { el ->
                val o = el.jsonObject
                MultiFileEdit(
                    oldString = o["old_string"]?.jsonPrimitive?.content
                        ?: error("Falta 'old_string' en una edición"),
                    newString = o["new_string"]?.jsonPrimitive?.content
                        ?: error("Falta 'new_string' en una edición"),
                    replaceAll = o["replace_all"]?.jsonPrimitive?.booleanOrNull == true
                )
            }
        }.getOrElse { e -> return FsToolUtil.errorPayload(json, e.message ?: "Edición inválida") }

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        // Confirmación con diff cuando fsPreviewEdits está activo (YOLO lo salta).
        val previewEdits = preferences.current().fsPreviewEdits
        if (previewEdits && confirm != null) {
            val previewDiff = buildPreviewDiff(edits)
            val approved = confirm.requestApproval(
                title = "multi_edit: ${path.substringAfterLast('/')}",
                detail = "Aplica ${edits.size} cambio(s) de forma atómica.",
                diff = previewDiff
            )
            if (!approved) return FsToolUtil.errorPayload(json, "Operación rechazada por el usuario")
        }

        return FsToolUtil.fsResultToJson(json, agent.multiEditFile(abs, edits))
    }

    companion object {
        const val TOOL_NAME = "multi_edit"

        fun buildPreviewDiff(edits: List<MultiFileEdit>): String = buildString {
            edits.forEachIndexed { i, edit ->
                appendLine("@@ edit ${i + 1}/${edits.size} @@")
                for (line in edit.oldString.lines()) appendLine("- $line")
                for (line in edit.newString.lines()) appendLine("+ $line")
                if (i < edits.size - 1) appendLine()
            }
        }.trimEnd()
    }
}
