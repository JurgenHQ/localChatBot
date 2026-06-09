package com.localchatbot.core.car

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mensaje del asistente destinado al coche. Texto plano (sin markdown),
 * pensado para que el sistema (Android Auto / CarPlay) lo lea por TTS.
 */
data class CarMessage(
    val id: String,
    val text: String,
    val timestampEpochMs: Long
)

/**
 * Puente entre el dominio y las capas de plataforma del modo coche
 * (análogo a [com.localchatbot.core.state.ActiveSessionStore]).
 *
 * `CarSessionManager` publica aquí cada respuesta COMPLETA del asistente;
 * androidMain la convierte en notificación `MessagingStyle` y iosMain en
 * donación de intent + notificación de comunicación. Las plataformas
 * coleccionan [incoming] (eventos one-shot) y pueden leer [conversation]
 * para reconstruir el hilo visible en el coche.
 */
class CarMessageStore {

    private val _conversation = MutableStateFlow<List<CarMessage>>(emptyList())
    val conversation: StateFlow<List<CarMessage>> = _conversation.asStateFlow()

    // extraBufferCapacity: si nadie colecciona en el momento del emit (la capa
    // de plataforma aún no se ha suscrito), el mensaje no se pierde ni bloquea.
    private val _incoming = MutableSharedFlow<CarMessage>(extraBufferCapacity = 16)
    val incoming: SharedFlow<CarMessage> = _incoming.asSharedFlow()

    fun publish(message: CarMessage) {
        _conversation.value = (_conversation.value + message).takeLast(MAX_CONVERSATION_SIZE)
        _incoming.tryEmit(message)
    }

    fun clear() {
        _conversation.value = emptyList()
    }

    private companion object {
        /** El coche solo necesita el hilo reciente; el histórico completo vive en ChatRepository. */
        const val MAX_CONVERSATION_SIZE = 20
    }
}
