package com.localchatbot.core.state

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marcador de contexto de corutina con la sesión a la que pertenece el turno en curso.
 *
 * `SendMessageUseCase` lo instala con `withContext` alrededor de la ejecución de tools y se
 * propaga por el árbol de corutinas del turno (incluida la ejecución paralela), igual que
 * `AutoApproveConfirmations`. Tools cuyo estado es por-sesión (`ask_user`, `manage_todos`)
 * deben resolver la sesión leyendo esto en vez de `ActiveSessionStore.activeSessionId`: ese
 * store refleja qué sesión está *visible* en la UI, y un turno se lanza en `applicationScope`
 * (sobrevive a que el usuario cambie de sesión mientras corre), así que ambas pueden divergir.
 */
class TurnSessionContext(
    val sessionId: String,
    /**
     * Sesión contra la que se resuelven el **workspace efectivo** y el **modo Plan/Build**
     * ([ActiveWorkspaceStore]). Normalmente es la misma que [sessionId]; difiere solo en los
     * sub-agentes, cuya sesión hija está agrupada bajo `SUBAGENTS_GROUP_ID` (que no es un
     * proyecto real) y por tanto caería al workspace global en vez de trabajar sobre el del
     * proyecto del padre, que es sobre el que se le encargó la tarea.
     */
    val workspaceSessionId: String = sessionId
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TurnSessionContext>
}
