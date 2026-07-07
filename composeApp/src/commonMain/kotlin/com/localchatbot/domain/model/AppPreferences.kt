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
    val fsAllowOutsideWorkspace: Boolean = false,
    /**
     * Si está activo, `edit_file` y `multi_edit` muestran un diff de los cambios
     * antes de aplicarlos, incluso sin YOLO. YOLO sigue saltando la confirmación.
     * Por defecto desactivado para preservar la autonomía del agente.
     */
    val fsPreviewEdits: Boolean = false,
    /**
     * Modo del agente: [AgentMode.Build] (puede crear/editar) o [AgentMode.Plan]
     * (solo lectura — las tools que mutan el proyecto se desactivan). Build por defecto.
     */
    val agentMode: AgentMode = AgentMode.Build,
    val installedSkills: List<InstalledSkill> = emptyList(),
    val customSkills: List<SkillDefinition> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * Si está activo, el desktop levanta un servidor HTTP/WebSocket en la LAN/VPN
     * para revisar y aprobar cambios desde otro dispositivo. Apagado por defecto.
     */
    val remoteAccessEnabled: Boolean = false,
    /** Puerto del servidor de acceso remoto. */
    val remoteAccessPort: Int = 7676,
    /** PIN que los dispositivos remotos deben introducir. Generado al activar. */
    val remoteAccessPin: String = "",
    /**
     * Última URL del visor remoto embebido (Fase 1b). La app abre esta web (el
     * cliente remoto servido por otro desktop) dentro de un WebView, sin navegador
     * externo. Se recuerda entre sesiones.
     */
    val remoteViewerUrl: String = "",
    /** Parámetros de generación globales (temperature, topP, maxTokens, etc.). */
    val generationParams: GenerationParams = GenerationParams()
) {
    /** La búsqueda web está activa cuando hay una API key configurada. */
    val webSearchEnabled: Boolean get() = tavilyApiKey.isNotBlank()

    /**
     * URL efectiva del servicio multimedia.
     * - Si está configurada explícitamente, se usa siempre.
     * - Si no, se deriva del host (puerto 8080) cuando es un endpoint HTTP local.
     * - Para endpoints HTTPS/cloud no se puede derivar: el usuario debe configurarla a mano.
     */
    val effectiveImageServiceUrl: String
        get() = imageServiceUrl.ifBlank {
            if (connection.ip.isNotBlank() && !connection.useHttps)
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
            fsAllowOutsideWorkspace = false,
            fsPreviewEdits = false,
            agentMode = AgentMode.Build,
            installedSkills = emptyList(),
            customSkills = emptyList(),
            mcpServers = emptyList(),
            remoteAccessEnabled = false,
            remoteAccessPort = 7676,
            remoteAccessPin = "",
            remoteViewerUrl = "",
            generationParams = GenerationParams()
        )
    }
}
