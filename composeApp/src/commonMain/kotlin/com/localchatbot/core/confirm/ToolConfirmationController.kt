package com.localchatbot.core.confirm

import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Excepción: con [force] true el diálogo se muestra siempre, incluso en YOLO
 * (usado por run_command cuando el comando matchea la denylist de patrones
 * destructivos).
 */
class ToolConfirmationController(
    private val prefs: PreferencesRepository
) {

    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    private var counter: Long = 0

    /**
     * Serializa las solicitudes: el slot de UI es único, y en YOLO la ruta de
     * ejecución de tools es paralela — sin mutex, dos confirmaciones forzadas
     * simultáneas (denylist) se pisarían y una quedaría suspendida para siempre.
     */
    private val mutex = Mutex()

    suspend fun requestApproval(title: String, detail: String?, diff: String? = null, force: Boolean = false): Boolean {
        if (!force && prefs.current().fsYoloMode) return true
        return mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            val confirmation = PendingConfirmation(
                id = nextId(),
                title = title,
                detail = detail,
                diff = diff,
                response = deferred
            )
            _pending.update { confirmation }
            try {
                deferred.await()
            } finally {
                // Solo limpiamos cuando seguimos siendo la confirmación visible.
                _pending.update { current -> if (current?.id == confirmation.id) null else current }
            }
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
    /** Diff unificado (líneas con `+`/`-`) para mostrar coloreado en el diálogo. */
    val diff: String? = null,
    val response: CompletableDeferred<Boolean>
)
