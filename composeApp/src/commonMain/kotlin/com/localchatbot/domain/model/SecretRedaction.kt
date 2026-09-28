package com.localchatbot.domain.model

/**
 * Qué se quita de un servidor MCP al exportar la configuración sin secretos, y cómo se
 * rellena al importar ese archivo sobre una configuración que ya los tiene.
 *
 * No se vacían todos los headers ni todo el env: `Accept`, `NODE_ENV` o `LOG_LEVEL` no son
 * secretos y perderlos obligaría a reconfigurar el servidor a mano. Se vacían los valores
 * cuyo **nombre** indica credencial.
 */
object SecretRedaction {
    private val SENSITIVE = Regex("""auth|token|key|secret|passw|pwd|cookie|session|credential|bearer""", RegexOption.IGNORE_CASE)

    fun isSensitiveName(name: String): Boolean = SENSITIVE.containsMatchIn(name)

    fun redactMcp(server: McpServerConfig): McpServerConfig = server.copy(
        headers = server.headers.mapValues { (k, v) -> if (isSensitiveName(k)) "" else v },
        env = server.env.mapValues { (k, v) -> if (isSensitiveName(k)) "" else v }
    )

    /** Los valores vacíos de [imported] se completan con los de [current] (mismo servidor). */
    fun fillRedacted(imported: McpServerConfig, current: McpServerConfig): McpServerConfig = imported.copy(
        headers = imported.headers.mapValues { (k, v) -> v.ifEmpty { current.headers[k].orEmpty() } },
        env = imported.env.mapValues { (k, v) -> v.ifEmpty { current.env[k].orEmpty() } }
    )
}
