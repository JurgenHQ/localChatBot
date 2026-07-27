package com.localchatbot.presentation.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.localchatbot.core.theme.AppTheme
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.ChatMessage
import com.localchatbot.domain.model.ChatSession
import com.localchatbot.domain.model.SessionSummary
import com.localchatbot.domain.model.Role

@Composable
fun PreviewSurface(
    themeMode: ThemeMode = ThemeMode.Light,
    content: @Composable () -> Unit
) {
    AppTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background
        ) { content() }
    }
}

object PreviewData {
    private const val nowMs = 1_715_000_000_000L

    val userMessage = ChatMessage(
        id = "m1",
        role = Role.User,
        content = "¿Puedes revisar este middleware de auth? Creo que el flujo de refresh-token tiene un bug.",
        timestampEpochMs = nowMs
    )

    val assistantMessage = ChatMessage(
        id = "m2",
        role = Role.Assistant,
        content = "Claro, mírame el código. Por lo que describes podría ser una de tres cosas:\n\n1. El token se está cacheando antes de validarlo\n2. El refresh no espera la respuesta antes de reintentar\n3. Falta invalidar la sesión vieja al rotar",
        timestampEpochMs = nowMs + 1_000
    )

    val userFollowUp = ChatMessage(
        id = "m3",
        role = Role.User,
        content = "Es el caso 2. Mira el handler.",
        timestampEpochMs = nowMs + 2_000
    )

    val activeSession = ChatSession(
        id = "s1",
        title = "Refactor de auth",
        model = "llama-3.1-8b-instruct",
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs + 2_000,
        messages = listOf(userMessage, assistantMessage, userFollowUp)
    )

    val emptySession = ChatSession(
        id = "s0",
        title = "Nueva conversación",
        model = "llama-3.1-8b-instruct",
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        messages = emptyList()
    )

    /**
     * El drawer consume [SessionSummary], no [ChatSession]: solo necesita metadatos y el
     * preview del último mensaje, nunca la lista de mensajes (ver `ChatRepository`).
     */
    val sessionList = listOf(
        SessionSummary(
            id = "s1",
            title = "Refactor de auth",
            model = "llama-3.1-8b-instruct",
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs + 2_000,
            lastMessagePreview = "Es el caso 2. Mira el handler."
        ),
        SessionSummary(
            id = "s2",
            title = "Diseño de tabla SQL",
            model = "llama-3.1-8b-instruct",
            createdAtEpochMs = nowMs - 10_000,
            updatedAtEpochMs = nowMs - 10_000,
            lastMessagePreview = "Te recomendaría un índice compuesto…"
        ),
        SessionSummary(
            id = "s3",
            title = "Error de compilación",
            model = "llama-3.1-8b-instruct",
            createdAtEpochMs = nowMs - 20_000,
            updatedAtEpochMs = nowMs - 20_000,
            lastMessagePreview = "Posiblemente sea el classpath…"
        )
    )
}
