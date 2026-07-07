package com.localchatbot.domain.tools

import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.data.remote.FunctionDefinition
import com.localchatbot.data.remote.ToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TodoItem(val id: String, val text: String, val done: Boolean = false)

/**
 * Todos scoped per chat session — la lista de una sesión NUNCA aparece en otra.
 * Si no hay sesión activa cuando se llama la tool, se usa la clave "" como fallback.
 */
class TodoTool(
    private val activeSessionStore: ActiveSessionStore
) : Tool {

    override val name: String = TOOL_NAME

    private val items = mutableMapOf<String, MutableList<TodoItem>>()
    private val mutex = Mutex()
    private var counter = 0L

    private val _state = MutableStateFlow<Map<String, List<TodoItem>>>(emptyMap())
    val state: StateFlow<Map<String, List<TodoItem>>> = _state.asStateFlow()

    /** Lista de la sesión dada (vacía si no existe). */
    fun itemsFor(sessionId: String?): List<TodoItem> =
        if (sessionId == null) emptyList() else _state.value[sessionId].orEmpty()

    private fun currentSessionKey(): String = activeSessionStore.activeSessionId.value ?: ""

    private fun publish() {
        _state.value = items.mapValues { it.value.toList() }
    }

    /** Texto normalizado para detectar duplicados (trim + lowercase + colapsa espacios). */
    private fun norm(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Fragmento JSON con los contadores de la sesión, para mantener al modelo consciente del progreso. */
    private fun counts(list: List<TodoItem>): String {
        val done = list.count { it.done }
        val pending = list.size - done
        return """"pending":$pending,"done":$done"""
    }

    override val definition: ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = TOOL_NAME,
            description = "Manage a to-do list for planning and tracking multi-step tasks in this chat session. " +
                "Operations: add (create one or more tasks), complete (mark done — use the id returned by add), list, clear. " +
                "Mark each task complete as soon as that step finishes; do NOT batch completions at the end. " +
                "Duplicate task texts are ignored automatically, so it is safe to plan the whole task up front.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("operation", buildJsonObject {
                        put("type", "string")
                        put("description", "One of: add | complete | list | clear")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("add"))
                            add(JsonPrimitive("complete"))
                            add(JsonPrimitive("list"))
                            add(JsonPrimitive("clear"))
                        })
                    })
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Single task description for 'add' (use 'texts' to add several at once)")
                    })
                    put("texts", buildJsonObject {
                        put("type", "array")
                        put("description", "Several task descriptions to add in one call — preferred for planning a multi-step task up front")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task ID returned by a previous 'add' — required for 'complete'")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("operation")) })
            }
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { return """{"error":"Invalid JSON arguments"}""" }
        val op = obj["operation"]?.jsonPrimitive?.content
            ?: return """{"error":"Missing 'operation'"}"""

        val key = currentSessionKey()

        return mutex.withLock {
            val list = items.getOrPut(key) { mutableListOf() }
            when (op) {
                "add" -> {
                    val texts = obj["texts"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }
                        ?: obj["text"]?.jsonPrimitive?.content?.let { listOf(it) }
                        ?: return@withLock """{"error":"'text' or 'texts' required for add"}"""
                    if (texts.isEmpty()) return@withLock """{"error":"'texts' must not be empty"}"""

                    val added = texts.map { text ->
                        // Dedup contra pendientes: si ya existe un todo no completado con
                        // el mismo texto normalizado, reusamos su id en vez de duplicar.
                        val existing = list.firstOrNull { !it.done && norm(it.text) == norm(text) }
                        if (existing != null) {
                            Triple(text, existing.id, true)
                        } else {
                            val id = "todo-${Clock.System.now().toEpochMilliseconds()}-${counter++}"
                            list.add(TodoItem(id, text))
                            Triple(text, id, false)
                        }
                    }
                    publish()

                    // Compat: una sola tarea individual → forma plana con id directo.
                    if (obj["texts"] == null && added.size == 1) {
                        val (_, id, dup) = added.first()
                        """{"ok":true,"id":"$id","duplicate":$dup,"reminder":"call complete with this id when the step is done",${counts(list)}}"""
                    } else {
                        val arr = added.joinToString(",") { (text, id, dup) ->
                            """{"text":"${text.replace("\"", "'")}","id":"$id","duplicate":$dup}"""
                        }
                        """{"ok":true,"added":[$arr],"reminder":"call complete with each id when that step is done",${counts(list)}}"""
                    }
                }
                "complete" -> {
                    val id = obj["id"]?.jsonPrimitive?.content
                        ?: return@withLock """{"error":"'id' required for complete"}"""
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx < 0) return@withLock """{"error":"Todo not found: $id"}"""
                    list[idx] = list[idx].copy(done = true)
                    publish()
                    """{"ok":true,${counts(list)}}"""
                }
                "list" -> {
                    val sb = StringBuilder("[")
                    list.forEachIndexed { i, item ->
                        if (i > 0) sb.append(",")
                        val status = if (item.done) "done" else "pending"
                        val safeText = item.text.replace("\"", "'")
                        sb.append("""{"id":"${item.id}","text":"$safeText","status":"$status"}""")
                    }
                    sb.append("]")
                    """{"todos":$sb}"""
                }
                "clear" -> {
                    list.clear()
                    publish()
                    """{"ok":true}"""
                }
                else -> """{"error":"Unknown operation: $op"}"""
            }
        }
    }

    /** Limpia todos los items de una sesión (p. ej. cuando se borra la sesión). */
    suspend fun clearSession(sessionId: String) = mutex.withLock {
        items.remove(sessionId)
        publish()
    }

    companion object {
        const val TOOL_NAME = "manage_todos"
    }
}
