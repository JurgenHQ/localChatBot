package com.localchatbot.core.hooks

import kotlinx.serialization.Serializable

/**
 * Comando que se ejecuta automáticamente después de que una tool muta el workspace:
 * formatear tras `edit_file`, recompilar, correr los tests…
 *
 * La salida se **añade al resultado de la tool**, así que el modelo la ve en la misma ronda:
 * si el formateador reescribe el archivo o el compilador falla, se entera de inmediato en vez
 * de seguir construyendo sobre algo roto.
 *
 * Con [event] = [EVENT_AFTER_TURN] el hook corre en cambio **al cerrar el turno** (correr los
 * tests cuando el agente dice que terminó), y solo si en el turno se tocó el workspace. Si
 * falla, su salida vuelve al modelo como instrucción efímera para que lo arregle antes de dar
 * el turno por terminado.
 *
 * @property tools nombres de tool que disparan el hook. Vacío = cualquier tool que mute.
 *   En un hook `after_turn`: solo corre si alguna de esas tools se usó en el turno.
 * @property command comando de shell, ejecutado en el workspace efectivo.
 * @property onlyOnFailureOutput si true, la salida solo se le pasa al modelo cuando el
 *   comando termina con error. Útil para un formateador que no tiene nada que decir cuando
 *   va bien y cuya salida solo gastaría contexto.
 */
@Serializable
data class AgentHook(
    val name: String = "",
    val tools: List<String> = emptyList(),
    val command: String = "",
    val enabled: Boolean = true,
    val timeoutSeconds: Int = 120,
    val onlyOnFailureOutput: Boolean = true,
    /** [EVENT_AFTER_TOOL] (por defecto, lo que había) o [EVENT_AFTER_TURN]. */
    val event: String = EVENT_AFTER_TOOL
) {
    private val active: Boolean get() = enabled && command.isNotBlank()

    /** Hook `after_tool` que aplica a [toolName]. */
    fun matches(toolName: String): Boolean =
        active && event == EVENT_AFTER_TOOL && (tools.isEmpty() || tools.contains(toolName))

    /** Hook `after_turn` que aplica a un turno en el que se usaron [toolsUsed] (que mutan). */
    fun firesAfterTurn(toolsUsed: Set<String>): Boolean =
        active && event == EVENT_AFTER_TURN && toolsUsed.isNotEmpty() &&
            (tools.isEmpty() || tools.any { it in toolsUsed })

    companion object {
        const val EVENT_AFTER_TOOL = "after_tool"
        const val EVENT_AFTER_TURN = "after_turn"
    }
}

@Serializable
data class AgentHooksConfig(
    val hooks: List<AgentHook> = emptyList()
)

/**
 * Contenido de ejemplo que se escribe la primera vez. Todo desactivado a propósito: un hook
 * que corre solo, sin que nadie lo haya pedido, es justo lo que no se quiere de un agente.
 */
val DEFAULT_HOOKS_JSON: String = """
{
  "hooks": [
    {
      "name": "Formatear tras editar (ejemplo, desactivado)",
      "tools": ["edit_file", "create_file", "multi_edit"],
      "command": "echo 'pon aqui tu formateador'",
      "enabled": false,
      "timeoutSeconds": 120,
      "onlyOnFailureOutput": true
    },
    {
      "name": "Compilar tras editar (ejemplo, desactivado)",
      "tools": ["edit_file", "create_file", "multi_edit"],
      "command": "./gradlew :composeApp:compileKotlinDesktop -q",
      "enabled": false,
      "timeoutSeconds": 300,
      "onlyOnFailureOutput": true
    },
    {
      "name": "Tests al terminar el turno (ejemplo, desactivado)",
      "event": "after_turn",
      "command": "./gradlew :composeApp:desktopTest -q",
      "enabled": false,
      "timeoutSeconds": 600,
      "onlyOnFailureOutput": true
    }
  ]
}
""".trimIndent()
