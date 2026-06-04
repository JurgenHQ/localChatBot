package com.localchatbot.di

import com.localchatbot.core.background.BackgroundExecutor
import com.localchatbot.core.background.createBackgroundExecutor
import com.localchatbot.core.debug.NetworkInspector
import com.localchatbot.core.image.ImageSaver
import com.localchatbot.core.image.createImageSaver
import com.localchatbot.core.network.HttpClientFactory
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.core.storage.SettingsFactory
import com.localchatbot.core.voice.SpeechRecognizer
import com.localchatbot.core.voice.TextToSpeech
import com.localchatbot.core.voice.VoiceConversationController
import com.localchatbot.data.remote.DiagramRenderApi
import com.localchatbot.data.remote.ImageGenApi
import com.localchatbot.data.remote.LmStudioApi
import com.localchatbot.data.remote.OpenAiApi
import com.localchatbot.data.remote.TavilyApi
import com.localchatbot.data.repository.ChatRepositoryImpl
import com.localchatbot.data.repository.ModelRepositoryImpl
import com.localchatbot.data.repository.PreferencesRepositoryImpl
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.tools.DiagramRenderTool
import com.localchatbot.domain.tools.ImageGenerationTool
import com.localchatbot.domain.tools.ToolRegistry
import com.localchatbot.domain.tools.WebSearchTool
import com.localchatbot.domain.usecase.CheckConnectionUseCase
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.ListModelsUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer {
    private val settings = SettingsFactory.create()
    private val httpClient = HttpClientFactory.create()
    private val json = HttpClientFactory.json
    val networkInspector = NetworkInspector()
    val imageSaver: ImageSaver = createImageSaver()
    private val openAiApi = OpenAiApi(httpClient, json, networkInspector)
    private val lmStudioApi = LmStudioApi(httpClient)
    private val imageGenApi = ImageGenApi(httpClient, json, networkInspector)
    private val diagramRenderApi = DiagramRenderApi(httpClient, json, networkInspector)
    private val tavilyApi = TavilyApi(httpClient, json, networkInspector)

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

    val preferencesRepository: PreferencesRepository = PreferencesRepositoryImpl(settings)
    val chatRepository: ChatRepository = ChatRepositoryImpl(settings, json)
    val modelRepository: ModelRepository = ModelRepositoryImpl(openAiApi, lmStudioApi)

    val activeSessionStore = ActiveSessionStore()
    val streamingStateStore = StreamingStateStore()
    val backgroundExecutor: BackgroundExecutor = createBackgroundExecutor()

    private val webSearchTool = WebSearchTool(tavilyApi, preferencesRepository, json)
    private val imageGenerationTool = ImageGenerationTool(imageGenApi, preferencesRepository, json)
    private val diagramRenderTool = DiagramRenderTool(diagramRenderApi, preferencesRepository, json)
    val toolRegistry = ToolRegistry(listOf(webSearchTool, imageGenerationTool, diagramRenderTool))

    val createSession = CreateSessionUseCase(chatRepository, preferencesRepository)
    val sendMessage = SendMessageUseCase(
        chats = chatRepository,
        model = modelRepository,
        prefs = preferencesRepository,
        toolRegistry = toolRegistry,
        streamingStateStore = streamingStateStore,
        json = json
    )
    val checkConnection = CheckConnectionUseCase(modelRepository)
    val listModels = ListModelsUseCase(modelRepository)

    val voiceController = VoiceConversationController(
        recognizer = SpeechRecognizer(),
        tts = TextToSpeech(),
        chatRepository = chatRepository,
        preferences = preferencesRepository,
        activeSessionStore = activeSessionStore,
        streamingStateStore = streamingStateStore,
        sendMessage = sendMessage,
        createSession = createSession,
        backgroundExecutor = backgroundExecutor,
        applicationScope = applicationScope
    )
}
