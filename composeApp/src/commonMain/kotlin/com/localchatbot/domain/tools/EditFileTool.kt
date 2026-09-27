package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.storage.EDIT_FILE_RECOVERY
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
 * Tool que edita un archivo existente. Soporta dos modos:
 * - String replace: `old_string` → `new_string` (match exacto).
 * - Line range: reemplaza las líneas `start_line`–`end_line` por `new_string`.
 *   Ideal para archivos grandes donde el match exacto es frágil.
 */
class EditFileTool(
    private val agent: FilesystemAgent,
    private val preferences: PreferencesRepository,
    private val json: Json,
    private val confirm: ToolConfirmationController? = null
) : Tool {

    override val name: String = TOOL_NAME
    // Sin confirmación: editar dentro del workspace está acotado por el sandbox de
    // paths (resolveSafePath + fsAllowOutsideWorkspace), igual que read_file. Pedir
    // aprobación en cada edit rompía la autonomía del agente. Las acciones realmente
    // irreversibles/peligrosas (delete_file, run_command destructivo) siguen gateadas.
    override val requiresConfirmation: Boolean = false

    override val activityLabel: String = "Editando archivo…"

    override fun activityDetail(argumentsJson: String): String? = runCatching {
        json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun isAvailable(): Boolean = FsToolUtil.isWriteAvailable(preferences)

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Edits an existing UTF-8 text file. Two modes — use exactly one:\n" +
                "MODE A · string replace: provide `old_string` + `new_string`. `old_string` must " +
                "match the file content EXACTLY (whitespace and indentation included) and appear " +
                "exactly once — include surrounding lines to make it unique. Pass replace_all=true " +
                "to replace every occurrence. NOTE: read_file prefixes lines with `N: ` for " +
                "navigation — strip that prefix before using text as old_string. As a safety net, " +
                "if the exact text isn't found, a whitespace-tolerant match (ignoring indentation / " +
                "trailing spaces) is attempted — but copying the exact text is still most reliable.\n" +
                "MODE B · line range: provide `start_line` + `end_line` + `new_string`. Replaces " +
                "lines start_line through end_line (1-based, inclusive) with new_string. Use this " +
                "for large files where exact string matching is fragile — read_file gives you the " +
                "exact line numbers. old_string must be omitted in this mode.\n" +
                "ALWAYS prefer this tool over rewriting a whole file with create_file.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Target file path. Relative paths resolve against the workspace.")
                    })
                    put("old_string", buildJsonObject {
                        put("type", "string")
                        put("description", "MODE A only. Exact text to replace. Must be unique unless replace_all=true.")
                    })
                    put("new_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Replacement text (both modes).")
                    })
                    put("replace_all", buildJsonObject {
                        put("type", "boolean")
                        put("description", "MODE A only. If true, replace every occurrence of old_string. Defaults to false.")
                    })
                    put("start_line", buildJsonObject {
                        put("type", "integer")
                        put("description", "MODE B only. 1-based line number where the replacement starts (inclusive).")
                    })
                    put("end_line", buildJsonObject {
                        put("type", "integer")
                        put("description", "MODE B only. 1-based line number where the replacement ends (inclusive). Defaults to start_line.")
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("new_string"))
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
        val newString = args["new_string"]?.jsonPrimitive?.content
            ?: return FsToolUtil.errorPayload(json, "Argumento 'new_string' faltante")

        val oldString = args["old_string"]?.jsonPrimitive?.content
        val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull == true
        val startLine = args["start_line"]?.jsonPrimitive?.intOrNull
        val endLine = args["end_line"]?.jsonPrimitive?.intOrNull

        // Validar que se usa exactamente un modo
        val lineMode = startLine != null
        val stringMode = oldString != null
        if (!lineMode && !stringMode) {
            return FsToolUtil.errorPayload(json, "Debes proporcionar 'old_string' (modo A) o 'start_line' (modo B).")
        }
        if (lineMode && stringMode) {
            return FsToolUtil.errorPayload(json, "Proporciona 'old_string' O 'start_line'/'end_line', no ambos a la vez.")
        }

        val abs = FsToolUtil.resolvePath(agent, preferences, json, path).getOrElse { e ->
            return FsToolUtil.errorPayload(json, e.message ?: "Path inválido")
        }

        // Diff-preview: cuando fsPreviewEdits está activo y es modo string,
        // muestra old → new antes de aplicar. Solo en modo string (el modo
        // líneas ya muestra start_line/end_line en el detail, que es suficiente).
        val previewEdits = preferences.current().fsPreviewEdits
        if (previewEdits && confirm != null && stringMode) {
            val diff = buildString {
                appendLine("@@ edit @@")
                for (line in oldString!!.lines()) appendLine("- $line")
                for (line in newString.lines()) appendLine("+ $line")
            }.trimEnd()
            val approved = confirm.requestApproval(
                title = "edit_file: ${path.substringAfterLast('/')}",
                detail = abs,
                diff = diff
            )
            if (!approved) return FsToolUtil.errorPayload(json, "Operación rechazada por el usuario")
        }

        return when (val result = agent.editFile(
            absPath = abs,
            oldString = oldString,
            newString = newString,
            replaceAll = replaceAll,
            startLine = startLine,
            endLine = endLine
        )) {
            is FsResult.Ok -> FsToolUtil.encode(json, result.payload)
            // Inyectamos los pasos de recuperación DIRECTO en el error (campo `recovery`):
            // el modelo siempre los ve, sin depender de que llame read_tool_docs.
            is FsResult.Err -> editErrorPayload(result.message)
        }
    }

    private fun editErrorPayload(message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", false)
                put("error", message)
                put("recovery", EDIT_FILE_RECOVERY)
            }
        )

    companion object {
        const val TOOL_NAME = "edit_file"
    }
}
