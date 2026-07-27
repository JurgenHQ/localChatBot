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
 * @property tools nombres de tool que disparan el hook. Vacío = cualquier tool que mute.
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
    val onlyOnFailureOutput: Boolean = true
) {
    fun matches(toolName: String): Boolean =
        enabled && command.isNotBlank() && (tools.isEmpty() || tools.contains(toolName))
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
    }
  ]
}
""".trimIndent()
