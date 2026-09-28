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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    /**
     * Tokens válidos emitidos tras un login con PIN correcto → instante de emisión. Caducan a
     * las [TOKEN_TTL_MS]: con un token se pueden aprobar confirmaciones y mandar órdenes al
     * agente (en YOLO, ejecutar comandos en esta máquina), así que uno filtrado no puede
     * servir para siempre.
     */
    private val tokens = ConcurrentHashMap<String, Long>()

    /**
     * Fallos de PIN por IP remota. El PIN es de 6 dígitos: sin límite de intentos se prueba
     * entero en minutos desde la misma red.
     */
    private val authFailures = ConcurrentHashMap<String, AuthFailures>()

    /** Fallos recientes de todas las IPs juntas, contra un ataque repartido entre muchas. */
    private val globalFailures = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    private class AuthFailures {
        var count = 0
        var windowStartMs = 0L
        var lockedUntilMs = 0L
        /** Bloqueos ya sufridos: cada uno dobla la duración del siguiente. */
        var lockouts = 0
    }

    private fun now() = System.currentTimeMillis()

    private fun isTokenValid(token: String): Boolean {
        val issuedAt = tokens[token] ?: return false
        if (now() - issuedAt > TOKEN_TTL_MS) {
            tokens.remove(token)
            return false
        }
        return true
    }

    private fun issueToken(): String {
        val cutoff = now() - TOKEN_TTL_MS
        tokens.entries.removeIf { it.value < cutoff }
        // Tope de sesiones remotas simultáneas: descartar las más viejas.
        while (tokens.size >= MAX_TOKENS) {
            val oldest = tokens.entries.minByOrNull { it.value }?.key ?: break
            tokens.remove(oldest)
        }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        tokens[token] = now()
        return token
    }

    /** Ms que le quedan de bloqueo a [ip] (o a todos, si saltó el límite global); 0 si puede intentar. */
    private fun lockRemainingMs(ip: String): Long {
        val t = now()
        while (true) {
            val head = globalFailures.peekFirst() ?: break
            if (t - head > GLOBAL_WINDOW_MS) globalFailures.pollFirst() else break
        }
        if (globalFailures.size >= GLOBAL_MAX_FAILURES) {
            val oldest = globalFailures.peekFirst() ?: t
            return (oldest + GLOBAL_WINDOW_MS - t).coerceAtLeast(1)
        }
        val f = authFailures[ip] ?: return 0
        synchronized(f) { return (f.lockedUntilMs - t).coerceAtLeast(0) }
    }

    private fun registerFailure(ip: String) {
        val t = now()
        globalFailures.addLast(t)
        val f = authFailures.computeIfAbsent(ip) { AuthFailures() }
        synchronized(f) {
            if (t - f.windowStartMs > FAILURE_WINDOW_MS) {
                f.count = 0
                f.windowStartMs = t
            }
            f.count++
            if (f.count >= MAX_FAILURES_PER_IP) {
                val lock = (BASE_LOCKOUT_MS shl f.lockouts.coerceAtMost(4)).coerceAtMost(MAX_LOCKOUT_MS)
                f.lockedUntilMs = t + lock
                f.lockouts++
                f.count = 0
            }
        }
    }

    /**
     * Comparación en tiempo constante: `==` sobre String corta en el primer carácter
     * distinto, y el tiempo de respuesta filtraría cuántos dígitos del PIN son correctos.
     */
    private fun pinMatches(sent: String): Boolean {
        val expected = pin
        if (expected.isEmpty()) return false
        // Sin distinguir mayúsculas: el PIN generado es alfanumérico en mayúsculas y en el
        // teclado del móvil es fácil escribirlo en minúsculas. No resta entropía (el
        // alfabeto ya es solo de mayúsculas).
        return java.security.MessageDigest.isEqual(
            sent.trim().uppercase().toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    /** Job del stream activo, para poder cancelarlo desde el remoto. */
    private var activeStreamJob: Job? = null

    /**
     * Sesión activa con sus mensajes, emparejada con su id. Igual que en `ChatViewModel`:
     * se resuelve por id en vez de buscarla en una lista con todo el historial (para no
     * releer todas las sesiones en cada delta), y va emparejada con el id para que al
     * cambiar de conversación el remoto no muestre un instante el id nuevo con los mensajes
     * de la anterior.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeSessionFlow = deps.activeSessionStore.activeSessionId.flatMapLatest { id ->
        if (id == null) flowOf(null to null)
        else deps.chats.sessionWithMessages(id).map { session -> id to session }
    }

    /** Snapshot del estado completo en JSON, recalculado en cada cambio de cualquier fuente. */
    private val snapshotFlow = combine(
        combine(deps.chats.sessionSummaries, activeSessionFlow, deps.confirm.pending) { a, b, c -> Triple(a, b, c) },
        combine(deps.promptStore.prompts, deps.streamingStateStore.streaming, deps.prefs.preferences) { a, b, c -> Triple(a, b, c) }
    ) { (sessions, activePair, pending), (prompts, streaming, prefs) ->
        val (activeId, active) = activePair
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

    override fun start(port: Int, pin: String, host: String) {
        if (_running.value) stop()
        // Se guarda normalizado igual que lo que se compara en pinMatches.
        this.pin = pin.trim().uppercase()
        tokens.clear()
        authFailures.clear()
        globalFailures.clear()
        // Con una IP concreta (p. ej. la de Tailscale) el servidor solo es alcanzable por esa
        // interfaz. Si esa IP ya no existe (VPN caída), el bind falla y el servidor no
        // arranca: caer a 0.0.0.0 abriría en silencio justo lo que el usuario quiso cerrar.
        server = embeddedServer(CIO, port = port, host = host.ifBlank { "0.0.0.0" }) {
            install(WebSockets)
            routing {
                get("/") {
                    call.respondText(indexHtml(), ContentType.Text.Html)
                }
                post("/auth") {
                    // remoteAddress y no remoteHost: este último puede hacer DNS inverso.
                    val ip = call.request.local.remoteAddress
                    val locked = lockRemainingMs(ip)
                    if (locked > 0) {
                        val secs = (locked + 999) / 1000
                        call.response.headers.append(HttpHeaders.RetryAfter, secs.toString())
                        call.respondText(
                            """{"error":"Demasiados intentos. Prueba de nuevo en $secs s"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.TooManyRequests
                        )
                        return@post
                    }
                    val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                    val sentPin = body?.get("pin")?.jsonPrimitive?.content
                    if (sentPin != null && pinMatches(sentPin)) {
                        authFailures.remove(ip)
                        val token = issueToken()
                        call.respondText("""{"token":"$token"}""", ContentType.Application.Json)
                    } else {
                        registerFailure(ip)
                        // Frena la fuerza bruta incluso antes de llegar al bloqueo.
                        delay(FAILURE_DELAY_MS)
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
                    if (token == null || !isTokenValid(token)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val msgId = call.request.queryParameters["msg"]
                    // Las imágenes viven solo en el overlay en memoria del repositorio
                    // (nunca se persisten), así que se resuelven por id de mensaje sin
                    // recorrer sesión alguna.
                    val dataUrl = msgId?.let { deps.chats.messageImageDataUrl(it) }
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
                    if (token == null || !isTokenValid(token)) {
                        // 1008: el cliente lo distingue de un corte de red y vuelve al login
                        // en vez de reintentar para siempre con un token muerto.
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "token"))
                        return@webSocket
                    }
                    _clients.update { it + 1 }
                    val pushJob = launch {
                        snapshotFlow.collect { outgoing.send(Frame.Text(it)) }
                    }
                    // Una conexión abierta no sobrevive a su token: al caducar se cierra.
                    val expiryJob = launch {
                        val issuedAt = tokens[token] ?: now()
                        delay((issuedAt + TOKEN_TTL_MS - now()).coerceAtLeast(0))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "token"))
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleAction(frame.readText())
                        }
                    } finally {
                        pushJob.cancel()
                        expiryJob.cancel()
                        _clients.update { (it - 1).coerceAtLeast(0) }
                    }
                }
            }
        }
        // El bind puede fallar (puerto ocupado, IP elegida que ya no existe). Sin capturarlo
        // la excepción subía al collector de preferencias de AppContainer y lo mataba: el
        // servidor ya no reaccionaba a ningún cambio hasta reiniciar la app.
        _running.value = runCatching { server?.start(wait = false) }.isSuccess
        if (!_running.value) server = null
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

    private companion object {
        /** Vida de un token de sesión remota. Suficiente para una jornada sin re-login. */
        const val TOKEN_TTL_MS = 12L * 60 * 60 * 1000
        const val MAX_TOKENS = 16

        /** Fallos permitidos por IP dentro de [FAILURE_WINDOW_MS] antes de bloquearla. */
        const val MAX_FAILURES_PER_IP = 5
        const val FAILURE_WINDOW_MS = 10L * 60 * 1000
        /** Primer bloqueo; cada bloqueo siguiente de la misma IP dobla, hasta [MAX_LOCKOUT_MS]. */
        const val BASE_LOCKOUT_MS = 60L * 1000
        const val MAX_LOCKOUT_MS = 60L * 60 * 1000

        /** Tope de fallos de todas las IPs en [GLOBAL_WINDOW_MS]; al pasarlo, /auth se cierra para todos. */
        const val GLOBAL_MAX_FAILURES = 30
        const val GLOBAL_WINDOW_MS = 10L * 60 * 1000

        const val FAILURE_DELAY_MS = 750L
    }
}
