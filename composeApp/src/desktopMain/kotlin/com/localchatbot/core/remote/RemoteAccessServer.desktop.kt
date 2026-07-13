package com.localchatbot.core.remote

import com.localchatbot.domain.model.Role
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

actual fun createRemoteAccessServer(deps: RemoteAccessDeps): RemoteAccessServer =
    DesktopRemoteAccessServer(deps)

actual fun localIpAddresses(): List<String> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<java.net.Inet4Address>()
        .map { it.hostAddress }
        .filter { !it.startsWith("169.254.") }   // descarta link-local
        .distinct()
}.getOrDefault(emptyList())

private class DesktopRemoteAccessServer(
    private val deps: RemoteAccessDeps
) : RemoteAccessServer {

    private val json = Json { ignoreUnknownKeys = true }

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _clients = MutableStateFlow(0)
    override val connectedClients: StateFlow<Int> = _clients.asStateFlow()

    private var server: EmbeddedServer<*, *>? = null
    private var pin: String = ""

    /** Tokens válidos emitidos tras un login con PIN correcto. */
    private val tokens = ConcurrentHashMap.newKeySet<String>()

    /** Job del stream activo, para poder cancelarlo desde el remoto. */
    private var activeStreamJob: Job? = null

    /** Snapshot del estado completo en JSON, recalculado en cada cambio de cualquier fuente. */
    private val snapshotFlow = combine(
        combine(deps.chats.sessions, deps.activeSessionStore.activeSessionId, deps.confirm.pending) { a, b, c -> Triple(a, b, c) },
        combine(deps.promptStore.prompts, deps.streamingStateStore.streaming, deps.prefs.preferences) { a, b, c -> Triple(a, b, c) }
    ) { (sessions, activeId, pending), (prompts, streaming, prefs) ->
        buildJsonObject {
            put("type", "state")
            put("activeSessionId", activeId ?: "")
            put("streaming", activeId != null && activeId in streaming)
            put("yoloMode", prefs.fsYoloMode)
            put("sessions", buildJsonArray {
                sessions.sortedByDescending { it.updatedAtEpochMs }.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("title", s.title)
                        put("active", s.id == activeId)
                    })
                }
            })
            val active = sessions.firstOrNull { it.id == activeId }
            put("messages", buildJsonArray {
                active?.messages
                    ?.filter { m ->
                        m.role == Role.User ||
                        (m.role == Role.Assistant && m.content.isNotBlank()) ||
                        (m.role == Role.Assistant && !m.toolCalls.isNullOrEmpty()) ||
                        // Imagen generada: la burbuja del asistente puede tener content
                        // vacío pero llevar imageDataUrl — hay que mostrarla igual.
                        (m.role == Role.Assistant && m.imageDataUrl != null) ||
                        m.role == Role.Tool
                    }
                    ?.forEach { m ->
                        add(buildJsonObject {
                            put("id", m.id)
                            put("role", m.role.name)
                            put("content", m.content)
                            put("hasImage", m.imageDataUrl != null)
                            m.toolName?.let { put("toolName", it) }
                            m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                                put("toolCalls", buildJsonArray {
                                    calls.forEach { tc ->
                                        add(buildJsonObject {
                                            put("name", tc.name)
                                            put("args", tc.argumentsJson)
                                        })
                                    }
                                })
                            }
                        })
                    }
            })
            pending?.let { c ->
                put("pendingConfirmation", buildJsonObject {
                    put("id", c.id)
                    put("title", c.title)
                    put("detail", c.detail ?: "")
                    if (c.diff != null) put("diff", c.diff)
                })
            }
            (activeId?.let { prompts[it] })?.let { p ->
                put("pendingPrompt", buildJsonObject {
                    put("question", p.question)
                    put("allowFreeText", p.allowFreeText)
                    put("options", buildJsonArray { p.options.forEach { add(it) } })
                })
            }
        }
    }.map { json.encodeToString(JsonObject.serializer(), it) }

    override fun start(port: Int, pin: String) {
        if (_running.value) stop()
        this.pin = pin
        tokens.clear()
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/") {
                    call.respondText(indexHtml(), ContentType.Text.Html)
                }
                post("/auth") {
                    val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                    val sentPin = body?.get("pin")?.jsonPrimitive?.content
                    if (sentPin != null && sentPin == this@DesktopRemoteAccessServer.pin) {
                        val token = UUID.randomUUID().toString().replace("-", "")
                        tokens.add(token)
                        call.respondText("""{"token":"$token"}""", ContentType.Application.Json)
                    } else {
                        call.respondText(
                            """{"error":"PIN incorrecto"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                    }
                }
                // Sirve la imagen (usuario o generada) de un mensaje bajo demanda. No va
                // en el snapshot del WebSocket para no reenviar el base64 en cada token.
                get("/image") {
                    val token = call.request.queryParameters["token"]
                    if (token == null || token !in tokens) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val msgId = call.request.queryParameters["msg"]
                    val dataUrl = msgId?.let { id ->
                        deps.chats.sessions.first()
                            .firstNotNullOfOrNull { s -> s.messages.firstOrNull { it.id == id }?.imageDataUrl }
                    }
                    val decoded = dataUrl?.let { decodeDataUrl(it) }
                    if (decoded == null) {
                        call.respondText("not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    // La imagen de un mensaje es inmutable → cachear evita re-descargas y
                    // parpadeo cuando la lista se reconstruye en cada frame del streaming.
                    call.response.headers.append("Cache-Control", "private, max-age=86400, immutable")
                    call.respondBytes(decoded.second, ContentType.parse(decoded.first))
                }
                webSocket("/ws") {
                    val token = call.request.queryParameters["token"]
                    if (token == null || token !in tokens) {
                        close()
                        return@webSocket
                    }
                    _clients.update { it + 1 }
                    val pushJob = launch {
                        snapshotFlow.collect { outgoing.send(Frame.Text(it)) }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleAction(frame.readText())
                        }
                    } finally {
                        pushJob.cancel()
                        _clients.update { (it - 1).coerceAtLeast(0) }
                    }
                }
            }
        }.also { it.start(wait = false) }
        _running.value = true
    }

    override fun stop() {
        runCatching { server?.stop(500, 1000) }
        server = null
        tokens.clear()
        _clients.value = 0
        _running.value = false
    }

    /** Procesa una acción recibida del cliente remoto. */
    private fun handleAction(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "approve" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                val approved = runCatching { obj["approved"]!!.jsonPrimitive.boolean }.getOrDefault(false)
                deps.confirm.resolve(id, approved)
            }
            "selectSession" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                deps.activeSessionStore.set(id)
            }
            "newSession" -> deps.scope.launch {
                val s = deps.createSession()
                deps.activeSessionStore.set(s.id)
            }
            // answerPrompt y sendMessage comparten flujo: ambos envían un mensaje de usuario.
            "sendMessage", "answerPrompt" -> {
                val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.isEmpty()) return
                sendUserMessage(text)
            }
            "stop" -> {
                activeStreamJob?.cancel()
                activeStreamJob = null
                val activeId = deps.activeSessionStore.activeSessionId.value
                if (activeId != null) deps.streamingStateStore.stop(activeId)
            }
            "toggleYolo" -> deps.scope.launch {
                val current = deps.prefs.current().fsYoloMode
                deps.prefs.updateFsYoloMode(!current)
            }
        }
    }

    /** Espejo de ChatViewModel.send para enviar un mensaje desde el remoto. */
    private fun sendUserMessage(text: String) {
        activeStreamJob = deps.scope.launch {
            val activeId = deps.activeSessionStore.activeSessionId.value
            if (activeId != null && deps.streamingStateStore.isStreaming(activeId)) return@launch
            val sessionId = activeId ?: deps.createSession().id.also(deps.activeSessionStore::set)
            deps.promptStore.clear(sessionId)
            deps.streamingStateStore.start(sessionId)
            try {
                deps.sendMessage(sessionId, text)
            } finally {
                deps.streamingStateStore.stop(sessionId)
                activeStreamJob = null
            }
        }
    }

    /**
     * Parsea un data URL (`data:<mime>;base64,<datos>`) a (mime, bytes). Soporta
     * base64 y, por robustez, payload URL-encoded. Devuelve null si no es válido.
     */
    private fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray>? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val meta = dataUrl.substring(5, comma) // p.ej. "image/jpeg;base64"
        val mime = meta.substringBefore(';').ifBlank { "image/png" }
        val payload = dataUrl.substring(comma + 1)
        val bytes = runCatching {
            if (meta.contains("base64")) java.util.Base64.getDecoder().decode(payload)
            else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }.getOrNull() ?: return null
        return mime to bytes
    }

    /** Carga la SPA estática del cliente remoto desde resources. */
    private fun indexHtml(): String =
        this::class.java.classLoader?.getResourceAsStream("remote/index.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: "<html><body>Cliente remoto no encontrado.</body></html>"
}
