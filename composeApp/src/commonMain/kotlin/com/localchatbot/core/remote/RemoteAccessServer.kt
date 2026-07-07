package com.localchatbot.core.remote

import com.localchatbot.core.confirm.ToolConfirmationController
import com.localchatbot.core.state.ActiveSessionStore
import com.localchatbot.core.state.PendingUserPromptStore
import com.localchatbot.core.state.StreamingStateStore
import com.localchatbot.domain.repository.ChatRepository
import com.localchatbot.domain.repository.PreferencesRepository
import com.localchatbot.domain.usecase.CreateSessionUseCase
import com.localchatbot.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Dependencias para el servidor de acceso remoto. Todas son fuentes de verdad ya
 * reactivas que viven en [com.localchatbot.di.AppContainer].
 */
class RemoteAccessDeps(
    val chats: ChatRepository,
    val confirm: ToolConfirmationController,
    val promptStore: PendingUserPromptStore,
    val sendMessage: SendMessageUseCase,
    val createSession: CreateSessionUseCase,
    val activeSessionStore: ActiveSessionStore,
    val streamingStateStore: StreamingStateStore,
    val prefs: PreferencesRepository,
    val scope: CoroutineScope
)

/**
 * Servidor HTTP/WebSocket que expone los chats en la LAN/VPN para revisar y aprobar
 * cambios desde otro dispositivo. Sólo el `actual` de desktop tiene implementación
 * real; en móvil es un no-op (el agente y las fs tools sólo corren en desktop).
 */
interface RemoteAccessServer {
    val running: StateFlow<Boolean>
    val connectedClients: StateFlow<Int>
    fun start(port: Int, pin: String)
    fun stop()
}

/** Crea el servidor para la plataforma actual. */
expect fun createRemoteAccessServer(deps: RemoteAccessDeps): RemoteAccessServer

/** Direcciones IPv4 LAN/VPN de esta máquina (para mostrar la URL del remoto). Vacío en móvil. */
expect fun localIpAddresses(): List<String>

/** No-op: usado en plataformas sin servidor (móvil). */
class NoopRemoteAccessServer : RemoteAccessServer {
    override val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val connectedClients = kotlinx.coroutines.flow.MutableStateFlow(0)
    override fun start(port: Int, pin: String) {}
    override fun stop() {}
}
