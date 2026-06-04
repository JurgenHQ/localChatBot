package com.localchatbot.core.confirm

import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Coordina solicitudes de aprobación humana entre las tools (capa de datos)
 * y la UI (Compose).
 *
 * Las tools llaman [requestApproval] con un título legible y un detalle
 * (típicamente el path o el comando exacto). El controller publica un
 * [PendingConfirmation] en [pending] y suspende a la corutina llamante hasta
 * que la UI llame a [resolve] con la decisión del usuario.
 *
 * Cuando `prefs.current().fsYoloMode` está activo, el método retorna `true`
 * inmediatamente sin tocar el state flow — la tool se ejecuta sin diálogo.
 */
class ToolConfirmationController(
    private val prefs: PreferencesRepository
) {

    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    private var counter: Long = 0

    suspend fun requestApproval(title: String, detail: String?): Boolean {
        if (prefs.current().fsYoloMode) return true
        val deferred = CompletableDeferred<Boolean>()
        val confirmation = PendingConfirmation(
            id = nextId(),
            title = title,
            detail = detail,
            response = deferred
        )
        _pending.update { confirmation }
        return try {
            deferred.await()
        } finally {
            // Si dos tools se cruzaran (no debería pasar, pero por defensa) solo
            // limpiamos cuando seguimos siendo la confirmación visible.
            _pending.update { current -> if (current?.id == confirmation.id) null else current }
        }
    }

    /** Llamado por la UI al cerrar el diálogo. */
    fun resolve(id: String, approved: Boolean) {
        val current = _pending.value
        if (current?.id == id) {
            current.response.complete(approved)
            _pending.update { null }
        }
    }

    private fun nextId(): String = "conf-${++counter}"
}

data class PendingConfirmation(
    val id: String,
    val title: String,
    val detail: String?,
    val response: CompletableDeferred<Boolean>
)
