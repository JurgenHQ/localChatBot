package com.localchatbot.di

import com.localchatbot.core.automation.AutomationScheduler
import com.localchatbot.core.background.BackgroundExecutor
import com.localchatbot.core.background.createBackgroundExecutor
import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.platform.PlatformCapabilities
import com.localchatbot.core.platform.SystemNotifier
import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.fs.FilesystemAgent
import com.localchatbot.core.image.ImageSaver
import com.localchatbot.core.image.createImageSaver
import com.localchatbot.core.lifecycle.AppLifecycle
import com.localchatbot.core.lifecycle.createAppLifecycle
import com.localchatbot.core.network.HttpClientFactory
import com.localchatbot.core.remote.RemoteAccessDeps
import com.localchatbot.core.remote.RemoteAccessServer
import com.localchatbot.core.remote.createRemoteAccessServer
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.ActiveWorkspaceStore
import com.localchatbot.core.state.PendingUserPromptStore
import com.localchatbot.core.state.QueuedMessageStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.core.storage.SettingsFactory
import com.localchatbot.core.storage.CheckpointStore
import com.localchatbot.core.hooks.HooksStore
import com.localchatbot.core.hooks.createHooksStore
import com.localchatbot.core.storage.MemoryStore
import com.localchatbot.core.storage.SkillFileStore
import com.localchatbot.core.storage.ToolDocsStore
import com.localchatbot.core.storage.backupSettingsBeforeChatMigration
import com.localchatbot.core.storage.createLocalChatBotDatabase
import com.localchatbot.core.storage.createMemoryStore
import com.localchatbot.core.storage.createSkillFileStore
import com.localchatbot.core.storage.createToolDocsStore
import com.localchatbot.data.migration.ChatHistoryMigration
import com.localchatbot.core.voice.SpeechRecognizer
import com.localchatbot.core.voice.TextToSpeech
import com.localchatbot.core.voice.VoiceConversationController
import com.localchatbot.data.remote.DiagramRenderApi
import com.localchatbot.data.remote.ImageGenApi
import com.localchatbot.data.remote.LmStudioApi
import com.localchatbot.data.remote.OpenAiApi
import com.localchatbot.data.remote.TavilyApi
import com.localchatbot.data.remote.WebFetchApi
import com.localchatbot.data.remote.EmbeddingsApi
import com.localchatbot.core.index.SemanticIndexStore
import com.localchatbot.core.index.WorkspaceIndexer
import com.localchatbot.core.index.createSemanticIndexStore
import com.localchatbot.domain.tools.SearchCodeSemanticTool
import com.localchatbot.data.remote.VideoGenApi
import com.localchatbot.data.repository.ChatRepositoryImpl
import com.localchatbot.data.repository.ModelRepositoryImpl
import com.localchatbot.data.repository.PreferencesRepositoryImpl
import com.localchatbot.data.repository.ProjectRepositoryImpl
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.repository.ProjectRepository
import com.localchatbot.domain.tools.AnimateTool
import com.localchatbot.domain.tools.FsToolUtil
import com.localchatbot.domain.tools.AskUserTool
import com.localchatbot.domain.tools.CartoonTool
import com.localchatbot.domain.tools.CartoonVideoTool
import com.localchatbot.domain.tools.CreateDirectoryTool
import com.localchatbot.domain.tools.CreateFileTool
import com.localchatbot.domain.tools.DeleteFileTool
import com.localchatbot.domain.tools.DiagramRenderTool
import com.localchatbot.domain.tools.EditFileTool
import com.localchatbot.domain.tools.GenerateTextImageTool
import com.localchatbot.domain.tools.MultiEditTool
import com.localchatbot.domain.tools.ImageGenerationTool
import com.localchatbot.domain.tools.ListDirectoryTool
import com.localchatbot.domain.tools.SearchFilesTool
import com.localchatbot.domain.tools.ReadFileTool
import com.localchatbot.domain.tools.ReadMemoryTool
import com.localchatbot.domain.tools.ReadToolDocsTool
import com.localchatbot.domain.tools.RunCommandTool
import com.localchatbot.domain.tools.SaveImageTool
import com.localchatbot.domain.tools.SaveMemoryTool
import com.localchatbot.domain.tools.SaveVideoTool
import com.localchatbot.domain.tools.TodoTool
import com.localchatbot.domain.tools.ToolRegistry
import com.localchatbot.domain.skill.SkillCatalog
import com.localchatbot.domain.tools.ScriptToolFactory
import com.localchatbot.domain.tools.UseSkillTool
import com.localchatbot.domain.tools.FetchUrlTool
import com.localchatbot.domain.tools.GitCommitTool
import com.localchatbot.domain.tools.GitDiffTool
import com.localchatbot.domain.tools.GitLogTool
import com.localchatbot.domain.tools.GitStatusTool
import com.localchatbot.domain.tools.SpawnAgentTool
import com.localchatbot.domain.tools.WebSearchTool
import com.localchatbot.data.mcp.McpToolProvider
import com.localchatbot.domain.usecase.CheckConnectionUseCase
import com.localchatbot.domain.usecase.CompactContextUseCase
import com.localchatbot.domain.usecase.InitProjectUseCase
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.ListModelsUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AppContainer {
    private val settings = SettingsFactory.create()
    private val httpClient = HttpClientFactory.create()
    private val json = HttpClientFactory.json
    private val database = createLocalChatBotDatabase(json)

    init {
        // Migración one-shot del historial de chat (multiplatform-settings -> SQLDelight).
        // Corre bloqueante antes de exponer chatRepository: es la única forma de garantizar
        // que ningún lector vea la sesión a medio migrar. Ver ChatHistoryMigration.
        val chatMigrated = settings.getBoolean(KEY_CHAT_MIGRATED_TO_SQLDELIGHT_V1, false)
        if (!chatMigrated) {
            backupSettingsBeforeChatMigration()
            val migrated = runBlocking {
                ChatHistoryMigration(settings, database, json).migrateIfNeeded(alreadyMigrated = false)
            }
            if (migrated) settings.putBoolean(KEY_CHAT_MIGRATED_TO_SQLDELIGHT_V1, true)
        }
    }

    val networkInspector = NetworkInspector()
    val imageSaver: ImageSaver = createImageSaver()
    private val openAiApi = OpenAiApi(
        httpClient, json, networkInspector,
        authTokenProvider = { preferencesRepository.current().connection.apiKey.takeIf { it.isNotBlank() } }
    )
    private val lmStudioApi = LmStudioApi(
        httpClient, json, networkInspector,
        authTokenProvider = { preferencesRepository.current().connection.apiKey.takeIf { it.isNotBlank() } }
    )
    private val imageGenApi = ImageGenApi(httpClient, json, networkInspector)
    private val diagramRenderApi = DiagramRenderApi(httpClient, json, networkInspector)
    private val videoGenApi = VideoGenApi(httpClient, json, networkInspector)
    private val tavilyApi = TavilyApi(httpClient, json, networkInspector)
    private val webFetchApi = WebFetchApi(httpClient, networkInspector)
    private val embeddingsApi = EmbeddingsApi(
        httpClient, json, networkInspector,
        authTokenProvider = { preferencesRepository.current().connection.apiKey.takeIf { it.isNotBlank() } }
    )

    /**
     * Scope a nivel de aplicación. Las operaciones que deben sobrevivir a la
     * destrucción de los ViewModels (p. ej. el streaming de una respuesta)
     * se lanzan aquí en vez de en viewModelScope.
     *
     * Importante: usamos `Dispatchers.Default` (no Main) porque el stream
     * procesa cada delta llamando a JSON encode sobre toda la lista de
     * sesiones para persistir, y si en la sesión hay una imagen base64
     * grande (caso típico tras `generate_image` o `render_diagram`) eso
     * congela la UI. Las APIs nativas que requieren main (SpeechRecognizer,
     * foreground service) ya hacen su propio salto interno cuando hace falta.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val skillFileStore: SkillFileStore = createSkillFileStore()
    val toolDocsStore: ToolDocsStore = createToolDocsStore()
    val memoryStore: MemoryStore = createMemoryStore()
    val hooksStore: HooksStore = createHooksStore()
    val checkpointStore: CheckpointStore = CheckpointStore()
    val preferencesRepository: PreferencesRepository = PreferencesRepositoryImpl(settings, skillFileStore)
    val projectRepository: ProjectRepository = ProjectRepositoryImpl(settings, json)
    val chatRepository: ChatRepository = ChatRepositoryImpl(database, json)
    val modelRepository: ModelRepository = ModelRepositoryImpl(openAiApi, lmStudioApi)

    val activeSessionStore = ActiveSessionStore()
    val streamingStateStore = StreamingStateStore()

    /**
     * Workspace efectivo de la sesión activa (carpeta del proyecto asignado, o el global).
     * Lo consumen las tools fs (vía [FsToolUtil.workspaceStore], enlazado abajo) y el bloque
     * `<workspace>` del system prompt en [SendMessageUseCase].
     */
    val activeWorkspaceStore = ActiveWorkspaceStore(
        activeSessionStore = activeSessionStore,
        projectRepository = projectRepository,
        preferencesRepository = preferencesRepository,
        scope = applicationScope
    ).also { FsToolUtil.workspaceStore = it }

    /**
     * Preguntas pendientes que el modelo lanza al usuario vía `ask_user`.
     * La escribe [AskUserTool] y la observa [com.localchatbot.presentation.features.chat.ChatScreen].
     */
    val pendingUserPromptStore = PendingUserPromptStore()

    /** Mensajes escritos mientras el modelo trabajaba, pendientes de enviarse fusionados. */
    val queuedMessageStore = QueuedMessageStore()
    val backgroundExecutor: BackgroundExecutor = createBackgroundExecutor()
    val appLifecycle: AppLifecycle = createAppLifecycle()

    private val webSearchTool = WebSearchTool(tavilyApi, preferencesRepository, json)
    /** Leer una URL concreta. Sin API key: complementa a webSearchTool, que solo busca. */
    private val fetchUrlTool = FetchUrlTool(webFetchApi, json)

    private val imageGenerationTool = ImageGenerationTool(imageGenApi, preferencesRepository, json)
    private val diagramRenderTool = DiagramRenderTool(diagramRenderApi, preferencesRepository, json)
    private val generateTextImageTool = GenerateTextImageTool(imageGenApi, preferencesRepository, json)

    /**
     * Cadena de fallback para la imagen de entrada de cartoon/animate/cartoon-video: primero la
     * última imagen producida por otra tool de imagen (encadenable), si no la última foto que
     * subió el usuario (seteada por `SendMessageUseCase` en `activeSessionStore`).
     */
    private fun sourceImageForVideoTools(): String? =
        cartoonTool.peekProducedImage()
            ?: imageGenerationTool.peekProducedImage()
            ?: diagramRenderTool.peekProducedImage()
            ?: activeSessionStore.lastUserImageDataUrl.value

    private val cartoonTool = CartoonTool(
        imageGenApi, preferencesRepository, json,
        sourceImageProvider = {
            imageGenerationTool.peekProducedImage()
                ?: diagramRenderTool.peekProducedImage()
                ?: activeSessionStore.lastUserImageDataUrl.value
        }
    )
    private val animateTool = AnimateTool(videoGenApi, preferencesRepository, json, ::sourceImageForVideoTools)
    private val cartoonVideoTool = CartoonVideoTool(videoGenApi, preferencesRepository, json, ::sourceImageForVideoTools)

    /**
     * Agente local de filesystem y shell (solo desktop tiene impl real).
     * Expuesto como propiedad pública para que la UI pueda observarlo si lo
     * necesita en el futuro; las tools lo reciben por constructor.
     */
    val filesystemAgent: FilesystemAgent = FilesystemAgent()

    /** Notificaciones nativas + rebote del dock (real solo en desktop). */
    val systemNotifier = SystemNotifier()

    /**
     * Coordina las solicitudes de aprobación humana entre las tools (capa
     * datos/dominio) y la UI. El diálogo se renderiza en
     * [com.localchatbot.presentation.navigation.MainScaffold] (fuera del `when` de
     * pestañas, para que no dependa de estar en Chat). Recibe el notifier para
     * avisar por el SO cuando una tool queda esperando aprobación.
     *
     * Declarado DESPUÉS de [systemNotifier]: en Kotlin las propiedades se
     * inicializan en orden y al revés llegaría null.
     */
    val toolConfirmationController = ToolConfirmationController(preferencesRepository, systemNotifier)

    val todoTool = TodoTool(activeSessionStore)
    private val askUserTool =
        AskUserTool(activeSessionStore, pendingUserPromptStore, preferencesRepository, systemNotifier)
    val useSkillTool = UseSkillTool(
        installedSkillsProvider = { preferencesRepository.current().installedSkills },
        skillLookup = { id ->
            val prefs = preferencesRepository.current()
            SkillCatalog.byId(id, prefs.customSkills)
        }
    )
    private val createFileTool = CreateFileTool(filesystemAgent, toolConfirmationController, preferencesRepository, json)
    private val editFileTool = EditFileTool(filesystemAgent, preferencesRepository, json, toolConfirmationController)
    private val multiEditTool = MultiEditTool(filesystemAgent, toolConfirmationController, preferencesRepository, json)
    private val deleteFileTool = DeleteFileTool(filesystemAgent, toolConfirmationController, preferencesRepository, json)
    private val createDirectoryTool = CreateDirectoryTool(filesystemAgent, toolConfirmationController, preferencesRepository, json)
    private val readFileTool = ReadFileTool(filesystemAgent, preferencesRepository, json)
    private val listDirectoryTool = ListDirectoryTool(filesystemAgent, preferencesRepository, json)
    private val searchFilesTool = SearchFilesTool(filesystemAgent, preferencesRepository, json)

    /**
     * Índice de embeddings del workspace (archivo en `~/.localchatbot/semantic-index/`, no
     * SQLite: no hay migraciones de esquema que funcionen — ver `SemanticIndex`).
     */
    val semanticIndexStore: SemanticIndexStore = createSemanticIndexStore()
    val workspaceIndexer = WorkspaceIndexer(
        store = semanticIndexStore,
        embeddings = embeddingsApi,
        prefs = preferencesRepository,
        models = modelRepository,
        json = json
    )
    private val searchCodeSemanticTool = SearchCodeSemanticTool(workspaceIndexer, preferencesRepository, json)
    private val runCommandTool = RunCommandTool(filesystemAgent, toolConfirmationController, preferencesRepository, json)
    // Tools de git: las de lectura sin confirmación y disponibles en modo Plan; solo el
    // commit, que escribe, pide aprobación y exige Build.
    private val gitStatusTool = GitStatusTool(filesystemAgent, preferencesRepository, json)
    private val gitDiffTool = GitDiffTool(filesystemAgent, preferencesRepository, json)
    private val gitLogTool = GitLogTool(filesystemAgent, preferencesRepository, json)
    private val gitCommitTool =
        GitCommitTool(filesystemAgent, toolConfirmationController, preferencesRepository, json)
    private val saveImageTool = SaveImageTool(
        agent = filesystemAgent,
        confirm = toolConfirmationController,
        preferences = preferencesRepository,
        json = json,
        // Peek (no consume) la última imagen de cualquiera de las tools que generan
        // imágenes; el use case sigue adjuntándola al chat al cerrar la ronda.
        lastImageProvider = {
            imageGenerationTool.peekProducedImage()
                ?: diagramRenderTool.peekProducedImage()
                ?: cartoonTool.peekProducedImage()
        }
    )

    private val saveVideoTool = SaveVideoTool(
        agent = filesystemAgent,
        confirm = toolConfirmationController,
        preferences = preferencesRepository,
        json = json,
        lastVideoProvider = {
            animateTool.peekProducedVideo() ?: cartoonVideoTool.peekProducedVideo()
        }
    )

    /**
     * Declarado acá arriba (y no junto a los demás use cases) porque [spawnAgentTool] lo
     * necesita por constructor y las propiedades se inicializan en orden de declaración.
     */
    val createSession = CreateSessionUseCase(chatRepository, preferencesRepository)

    /**
     * Sub-agentes. La dependencia con [sendMessage] es circular (el use case necesita el
     * registry que contiene esta tool), así que se rompe con un provider perezoso: la
     * lambda no evalúa `sendMessage` hasta la primera ejecución de la tool, cuando ya está
     * construido.
     */
    private val spawnAgentTool = SpawnAgentTool(
        chats = chatRepository,
        projects = projectRepository,
        createSession = createSession,
        sendMessageProvider = { sendMessage },
        json = json
    )

    private val readToolDocsTool = ReadToolDocsTool(toolDocsStore, json)
    private val readMemoryTool = ReadMemoryTool(memoryStore, json)
    private val saveMemoryTool = SaveMemoryTool(memoryStore, toolConfirmationController, json)

    val toolRegistry = ToolRegistry(
        listOf(
            todoTool,
            askUserTool,
            useSkillTool,
            readToolDocsTool,
            readMemoryTool,
            saveMemoryTool,
            webSearchTool,
            fetchUrlTool,
            imageGenerationTool,
            diagramRenderTool,
            generateTextImageTool,
            cartoonTool,
            animateTool,
            cartoonVideoTool,
            saveImageTool,
            saveVideoTool,
            createFileTool,
            editFileTool,
            multiEditTool,
            deleteFileTool,
            createDirectoryTool,
            readFileTool,
            listDirectoryTool,
            searchFilesTool,
            searchCodeSemanticTool,
            runCommandTool,
            gitStatusTool,
            gitDiffTool,
            gitLogTool,
            gitCommitTool,
            spawnAgentTool
        )
    )

    private val scriptToolFactory = ScriptToolFactory(
        agent = filesystemAgent,
        confirm = toolConfirmationController,
        preferences = preferencesRepository,
        json = json,
        skillFileStore = skillFileStore
    )

    val mcpToolProvider = McpToolProvider(
        prefs = preferencesRepository,
        httpClient = httpClient,
        confirm = toolConfirmationController,
        json = json,
        inspector = networkInspector
    )

    // Tipo explícito: el provider perezoso de spawnAgentTool referencia esta propiedad, y sin
    // la anotación el inferidor entra en recursión (`Type checking has run into a recursive problem`).
    val sendMessage: SendMessageUseCase = SendMessageUseCase(
        chats = chatRepository,
        model = modelRepository,
        prefs = preferencesRepository,
        toolRegistry = toolRegistry,
        streamingStateStore = streamingStateStore,
        json = json,
        scope = applicationScope,
        scriptToolFactory = scriptToolFactory,
        mcpToolProvider = mcpToolProvider,
        confirm = toolConfirmationController,
        todoTool = todoTool,
        filesystemAgent = filesystemAgent,
        memoryStore = memoryStore,
        activeSessionStore = activeSessionStore,
        appLifecycle = appLifecycle,
        checkpointStore = checkpointStore,
        hooksStore = hooksStore,
        activeWorkspaceStore = activeWorkspaceStore
    )
    /** Compactación manual del contexto (`/compact`). */
    val compactContext = CompactContextUseCase(
        chats = chatRepository,
        model = modelRepository,
        prefs = preferencesRepository
    )
    /** Generación de AGENTS.md (`/init`). */
    val initProject = InitProjectUseCase(
        filesystemAgent = filesystemAgent,
        activeWorkspaceStore = activeWorkspaceStore,
        model = modelRepository,
        prefs = preferencesRepository
    )
    val checkConnection = CheckConnectionUseCase(modelRepository)
    val listModels = ListModelsUseCase(modelRepository)

    /**
     * Programador de tareas automatizadas. Solo se arranca en desktop (necesita la
     * app abierta y las tools locales/MCP); en móvil se construye pero no corre.
     */
    val automationScheduler = AutomationScheduler(
        prefs = preferencesRepository,
        chats = chatRepository,
        projects = projectRepository,
        createSession = createSession,
        sendMessage = sendMessage,
        scope = applicationScope,
        notifier = systemNotifier
    )

    /** TTS compartido: lo usa el modo voz (móvil) y el botón "leer" por mensaje (todas las plataformas). */
    val textToSpeech = TextToSpeech()

    val voiceController = VoiceConversationController(
        recognizer = SpeechRecognizer(),
        tts = textToSpeech,
        chatRepository = chatRepository,
        preferences = preferencesRepository,
        activeSessionStore = activeSessionStore,
        streamingStateStore = streamingStateStore,
        sendMessage = sendMessage,
        createSession = createSession,
        backgroundExecutor = backgroundExecutor,
        applicationScope = applicationScope
    )

    /**
     * Servidor de acceso remoto (real sólo en desktop). Arranca/para reactivamente
     * según las preferencias `remoteAccess*`. Se detiene en el shutdown hook de main.kt.
     */
    val remoteAccessServer: RemoteAccessServer = createRemoteAccessServer(
        RemoteAccessDeps(
            chats = chatRepository,
            confirm = toolConfirmationController,
            promptStore = pendingUserPromptStore,
            sendMessage = sendMessage,
            createSession = createSession,
            activeSessionStore = activeSessionStore,
            streamingStateStore = streamingStateStore,
            prefs = preferencesRepository,
            scope = applicationScope
        )
    )

    init {
        // Tareas automatizadas: solo en desktop (la app debe estar abierta y las
        // tools locales/MCP disponibles). En móvil el scheduler queda inerte.
        if (PlatformCapabilities.isDesktop) {
            automationScheduler.start()
        }
    }

    init {
        // Reacciona al toggle/puerto/PIN de acceso remoto.
        applicationScope.launch {
            preferencesRepository.preferences
                .map { Triple(it.remoteAccessEnabled, it.remoteAccessPort, it.remoteAccessPin) }
                .distinctUntilChanged()
                .collect { (enabled, port, pin) ->
                    if (enabled && pin.isNotBlank()) remoteAccessServer.start(port, pin)
                    else remoteAccessServer.stop()
                }
        }
    }
}

private const val KEY_CHAT_MIGRATED_TO_SQLDELIGHT_V1 = "chat_migrated_to_sqldelight_v1"
