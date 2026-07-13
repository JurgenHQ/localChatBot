package com.localchatbot.domain.tools

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.fs.FsResult
import com.localchatbot.core.fs.SafePathResult
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.state.ActiveWorkspaceStore
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Helpers compartidos por las tools de filesystem/shell.
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

    /**
     * Fuente única del **workspace efectivo** de la sesión activa (carpeta del proyecto o el
     * global). Se enlaza una vez en el arranque desde el DI. Si es null (p. ej. en tests o antes
     * del enlace) se cae al `fsWorkspaceDir` global de preferences, preservando el comportamiento
     * previo a la introducción de proyectos.
     */
    var workspaceStore: ActiveWorkspaceStore? = null

    /** Workspace efectivo actual: el del proyecto de la sesión activa, o el global como fallback. */
    private suspend fun effectiveWorkspace(prefs: PreferencesRepository): String? =
        workspaceStore?.current() ?: prefs.current().fsWorkspaceDir

    suspend fun isAvailable(prefs: PreferencesRepository): Boolean =
        PlatformCapabilities.isDesktop && effectiveWorkspace(prefs) != null

    /**
     * Como [isAvailable] pero además exige modo Build. Lo usan las tools que MUTAN el
     * proyecto (create/edit/multi_edit/delete/create_dir/save_image): en modo Plan
     * reportan no disponible y ni se envían al modelo, garantizando solo-lectura.
     */
    suspend fun isWriteAvailable(prefs: PreferencesRepository): Boolean =
        isAvailable(prefs) && effectiveAgentMode(prefs) == com.localchatbot.domain.model.AgentMode.Build

    /** Modo de agente efectivo de la sesión activa, con fallback al global de preferences. */
    private suspend fun effectiveAgentMode(prefs: PreferencesRepository): com.localchatbot.domain.model.AgentMode =
        workspaceStore?.currentAgentMode() ?: prefs.current().agentMode

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
            workspace = effectiveWorkspace(prefs),
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
