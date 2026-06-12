package com.localchatbot.data.mcp

import kotlinx.serialization.json.JsonObject

interface McpTransportLayer {
    /** Manda un request JSON-RPC (con id) y devuelve el cuerpo crudo de la respuesta. */
    suspend fun sendRequest(method: String, params: JsonObject?): Result<String>

    /**
     * Manda una notificación JSON-RPC (sin id). El server no responde, así que esto
     * NO espera ni lee respuesta — clave para no colgar el stdio en `readLine`.
     */
    suspend fun sendNotification(method: String, params: JsonObject?): Result<Unit>

    suspend fun close()
}
