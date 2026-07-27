package com.localchatbot.domain.model

import com.localchatbot.core.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * Corte de la compactación manual de una sesión.
 *
 * [appliedAtEpochMs] no es decorativo: la barra de contexto se apoya en el `contextTokens`
 * que mide el servidor, y sin saber **cuándo** se compactó no hay forma de distinguir una
 * medición vieja (que todavía incluía los mensajes ya descartados) de una posterior al
 * corte. Sin eso la barra se quedaba clavada en el número de antes.
 */
@Serializable
data class CompactBoundary(
    /** Último mensaje representado por el resumen. */
    val messageId: String,
    val appliedAtEpochMs: Long
)

@Serializable
data class PromptTemplate(
    val id: String,
    val title: String,
    val body: String
)

data class AppPreferences(
    /**
     * Perfiles de conexión disponibles (máx. 3). El usuario alterna entre ellos (p. ej.
     * "IA local" vs. una suscripción cloud); [connection] siempre refleja el activo.
     */
    val connectionProfiles: List<ConnectionProfile>,
    val activeConnectionProfileId: String,
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
     * Modelo de embeddings para el índice semántico del workspace (`search_code_semantic`),
     * servido por el mismo endpoint que el chat vía `/v1/embeddings`. Si está vacío, la tool
     * lo autodetecta de la lista de modelos (el primero cuyo id contenga "embed").
     */
    val embeddingsModel: String = "",
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
    /**
     * Override de [agentMode] por sesión (`sessionId -> AgentMode`). Cuando la sesión activa
     * tiene una entrada aquí, ese modo manda sobre [agentMode] (que actúa como valor por
     * defecto para sesiones sin override). Desktop only. Entradas de sesiones borradas son
     * inocuas (nunca se consultan).
     */
    val sessionAgentModes: Map<String, AgentMode> = emptyMap(),
    /**
     * `sessionId → corte de compactación manual` (`/compact`). Todo lo anterior al mensaje
     * del corte — él incluido — deja de enviarse al modelo y queda representado por
     * `ChatSession.contextSummary`; los mensajes siguen visibles en la UI.
     *
     * Vive en preferencias y no en la base porque **no hay migraciones de esquema que
     * funcionen** (ver CLAUDE.md → Persistence): una columna nueva en `session` llegaría
     * solo a bases nuevas, en silencio. Mismo patrón que [sessionAgentModes].
     */
    val sessionCompactBoundaries: Map<String, CompactBoundary> = emptyMap(),
    val installedSkills: List<InstalledSkill> = emptyList(),
    val customSkills: List<SkillDefinition> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * Tareas automatizadas que el agente dispara solo (a una hora o cada cierto
     * intervalo) mientras el desktop esté abierto. Solo desktop.
     */
    val scheduledTasks: List<ScheduledTask> = emptyList(),
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
    /**
     * Si está activo (solo desktop), el SO muestra una notificación nativa y el
     * icono del dock/taskbar rebota cuando termina una respuesta de chat o una
     * tarea programada. Activado por defecto.
     */
    val desktopNotificationsEnabled: Boolean = true,
    /** Parámetros de generación globales (temperature, topP, maxTokens, etc.). */
    val generationParams: GenerationParams = GenerationParams()
) {
    /** Config de conexión del perfil activo. Nunca vacía: [Default] ya trae un perfil. */
    val connection: ConnectionConfig
        get() = connectionProfiles.firstOrNull { it.id == activeConnectionProfileId }?.config
            ?: ConnectionConfig()

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
            connectionProfiles = listOf(
                ConnectionProfile(id = "default", name = "Perfil 1", config = ConnectionConfig(ip = "", port = "1234", model = ""))
            ),
            activeConnectionProfileId = "default",
            themeMode = ThemeMode.System,
            accentSeed = 0L,
            onboardingDone = false,
            tavilyApiKey = "",
            defaultSystemPrompt = "",
            promptTemplates = emptyList(),
            imageServiceUrl = "",
            embeddingsModel = "",
            fsWorkspaceDir = null,
            fsYoloMode = false,
            fsAllowOutsideWorkspace = false,
            fsPreviewEdits = false,
            agentMode = AgentMode.Build,
            installedSkills = emptyList(),
            customSkills = emptyList(),
            mcpServers = emptyList(),
            scheduledTasks = emptyList(),
            remoteAccessEnabled = false,
            remoteAccessPort = 7676,
            remoteAccessPin = "",
            remoteViewerUrl = "",
            desktopNotificationsEnabled = true,
            generationParams = GenerationParams()
        )
    }
}
