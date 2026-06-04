package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.SafePathResult
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Helpers compartidos por las 5 tools de filesystem/shell.
 *
 * Centraliza:
 * - Construcción de payloads de error JSON.
 * - Chequeo de "isAvailable" (desktop + workspace configurado).
 * - Resolución de paths con la política de sandbox.
 *
 * Las tools quedan pequeñas y consistentes. Si en el futuro queremos cambiar
 * la forma del payload, los logs, etc., solo hay un sitio.
 */
internal object FsToolUtil {

    suspend fun isAvailable(prefs: PreferencesRepository): Boolean =
        PlatformCapabilities.isDesktop && prefs.current().fsWorkspaceDir != null

    fun errorPayload(json: Json, message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("success", false)
                put("error", message)
            }
        )

    fun cancelledPayload(json: Json): String =
        errorPayload(json, "Acción cancelada por el usuario")

    fun encode(json: Json, payload: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), payload)

    /**
     * Resuelve [input] respetando la política configurada en preferences.
     * Devuelve `Right(absPath)` si la ruta es válida y aceptada, o `Left(payload)`
     * con el JSON de error listo para devolver al modelo.
     */
    suspend fun resolvePath(
        agent: FilesystemAgent,
        prefs: PreferencesRepository,
        json: Json,
        input: String
    ): Result<String> {
        val current = prefs.current()
        return when (val r = agent.resolveSafePath(
            workspace = current.fsWorkspaceDir,
            input = input,
            allowOutside = current.fsAllowOutsideWorkspace
        )) {
            is SafePathResult.Ok -> Result.success(r.absPath)
            is SafePathResult.Err -> Result.failure(IllegalArgumentException(r.message))
        }
    }

    /** Convierte un [FsResult] en string JSON para el modelo. */
    fun fsResultToJson(json: Json, result: FsResult): String = when (result) {
        is FsResult.Ok -> encode(json, result.payload)
        is FsResult.Err -> errorPayload(json, result.message)
    }
}
