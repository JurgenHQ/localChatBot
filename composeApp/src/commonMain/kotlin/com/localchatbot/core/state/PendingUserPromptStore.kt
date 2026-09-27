package com.localchatbot.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pregunta pendiente que el modelo lanzó al usuario vía la tool `ask_user`.
 * A diferencia de [com.localchatbot.core.confirm.ToolConfirmationController],
 * esto NO bloquea: la tool publica la pregunta, termina el turno, y la respuesta
 * del usuario llega como un mensaje normal en el siguiente turno (no como la
 * resolución de un `CompletableDeferred`).
 *
 * @property options opciones seleccionables; vacío = pregunta abierta.
 * @property allowFreeText si además de las opciones el usuario puede escribir libremente.
 */
data class PendingUserPrompt(
    val sessionId: String,
    val question: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = true
)

/**
 * Guarda la pregunta pendiente por sesión (una sesión NUNCA ve la de otra,
 * mismo criterio que [com.localchatbot.domain.tools.TodoTool]). La UI lee
 * [promptFor] de la sesión activa y la limpia al enviar la respuesta.
 */
class PendingUserPromptStore {
    private val _prompts = MutableStateFlow<Map<String, PendingUserPrompt>>(emptyMap())
    val prompts: StateFlow<Map<String, PendingUserPrompt>> = _prompts.asStateFlow()

    fun set(prompt: PendingUserPrompt) = _prompts.update { it + (prompt.sessionId to prompt) }

    fun clear(sessionId: String) = _prompts.update { it - sessionId }

    fun promptFor(sessionId: String?): PendingUserPrompt? =
        if (sessionId == null) null else _prompts.value[sessionId]
}
