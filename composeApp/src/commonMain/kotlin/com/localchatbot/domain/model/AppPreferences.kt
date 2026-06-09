package com.localchatbot.domain.model

import com.localchatbot.core.theme.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
data class PromptTemplate(
    val id: String,
    val title: String,
    val body: String
)

data class AppPreferences(
    val connection: ConnectionConfig,
    val themeMode: ThemeMode,
    val accentSeed: Long,
    val onboardingDone: Boolean,
    val tavilyApiKey: String = "",
    /** Instrucción inicial que se antepone como mensaje system en cada llamada al modelo. */
    val defaultSystemPrompt: String = "",
    /** Biblioteca local de plantillas de prompt reutilizables. */
    val promptTemplates: List<PromptTemplate> = emptyList(),
    /**
     * URL base del servicio multimedia (FastAPI). Expone tanto generación de imágenes
     * (vía ComfyUI/SDXL) como renderizado de diagramas (vía mermaid-cli).
     * Si está vacío, se deriva como `http://<connection.ip>:8080` automáticamente.
     */
    val imageServiceUrl: String = "",
    /**
     * Workspace para las tools de filesystem/shell. Solo se considera disponible
     * cuando está configurado un directorio absoluto. Se usa también como
     * working_dir por defecto para `run_command`.
     */
    val fsWorkspaceDir: String? = null,
    /**
     * Si está activo, las tools de filesystem/shell se ejecutan sin pedir
     * confirmación al usuario por cada llamada. Modo "Claude Code" — peligroso
     * pero conveniente.
     */
    val fsYoloMode: Boolean = false,
    /**
     * Si está activo, las tools pueden operar sobre paths fuera de
     * [fsWorkspaceDir] (incluyendo absolutos). Por defecto está apagado y la
     * tool retorna error sin pedir confirmación si la ruta escapa.
     */
    val fsAllowOutsideWorkspace: Boolean = false
) {
    /** La búsqueda web está activa cuando hay una API key configurada. */
    val webSearchEnabled: Boolean get() = tavilyApiKey.isNotBlank()

    /**
     * URL efectiva del servicio multimedia.
     * - Si está configurada explícitamente, se usa siempre.
     * - En modo Red local, se deriva de la IP de LM Studio (puerto 8080) si no se configuró.
     * - En modo URL directa, no se puede derivar automáticamente: el usuario debe configurarla
     *   a mano apuntando al tunnel del servicio multimedia.
     */
    val effectiveImageServiceUrl: String
        get() = imageServiceUrl.ifBlank {
            if (connection.mode == ConnectionMode.LocalNetwork && connection.ip.isNotBlank())
                "http://${connection.ip}:8080"
            else ""
        }

    companion object {
        val Default = AppPreferences(
            connection = ConnectionConfig(ip = "", port = "1234", model = ""),
            themeMode = ThemeMode.System,
            accentSeed = 0L,
            onboardingDone = false,
            tavilyApiKey = "",
            defaultSystemPrompt = "",
            promptTemplates = emptyList(),
            imageServiceUrl = "",
            fsWorkspaceDir = null,
            fsYoloMode = false,
            fsAllowOutsideWorkspace = false
        )
    }
}
