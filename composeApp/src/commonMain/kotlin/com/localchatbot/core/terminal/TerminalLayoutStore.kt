package com.localchatbot.core.terminal

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Dónde se acopla el panel de terminal, como en VS Code. */
enum class TerminalDock { Bottom, Right }

data class TerminalLayout(
    val dock: TerminalDock = TerminalDock.Bottom,
    /** Alto en dp cuando está abajo. */
    val height: Float = DEFAULT_HEIGHT,
    /** Ancho en dp cuando está a la derecha. */
    val width: Float = DEFAULT_WIDTH
) {
    /** Tamaño en el eje que se redimensiona según el acople actual. */
    val size: Float get() = if (dock == TerminalDock.Bottom) height else width

    companion object {
        const val DEFAULT_HEIGHT = 260f
        const val DEFAULT_WIDTH = 480f
        const val MIN_SIZE = 120f
    }
}

/**
 * Posición y tamaño del panel de terminal, persistidos para que se mantengan entre
 * arranques. Alto y ancho se guardan por separado: al cambiar de abajo a la derecha y
 * volver, cada acople recupera el tamaño que tenía.
 *
 * Es estado de UI puro, así que vive fuera de [com.localchatbot.domain.model.AppPreferences]
 * y no entra en el export de configuración.
 */
class TerminalLayoutStore(private val settings: Settings) {

    private val _layout = MutableStateFlow(load())
    val layout: StateFlow<TerminalLayout> = _layout.asStateFlow()

    fun toggleDock() = updateAndSave {
        it.copy(dock = if (it.dock == TerminalDock.Bottom) TerminalDock.Right else TerminalDock.Bottom)
    }

    /** Fija el tamaño del acople actual, sin persistir (se llama en cada frame del arrastre). */
    fun resize(size: Float) = _layout.update {
        if (it.dock == TerminalDock.Bottom) it.copy(height = size) else it.copy(width = size)
    }

    /** Persiste el tamaño actual: al soltar el asa, no en cada frame del arrastre. */
    fun commit() = save(_layout.value)

    fun resetSize() = updateAndSave {
        if (it.dock == TerminalDock.Bottom) it.copy(height = TerminalLayout.DEFAULT_HEIGHT)
        else it.copy(width = TerminalLayout.DEFAULT_WIDTH)
    }

    private fun updateAndSave(transform: (TerminalLayout) -> TerminalLayout) {
        save(_layout.updateAndGet(transform))
    }

    private fun load(): TerminalLayout {
        val default = TerminalLayout()
        return TerminalLayout(
            dock = settings.getStringOrNull(KEY_DOCK)
                ?.let { name -> TerminalDock.entries.firstOrNull { it.name == name } }
                ?: default.dock,
            height = settings.getFloat(KEY_HEIGHT, default.height),
            width = settings.getFloat(KEY_WIDTH, default.width)
        )
    }

    private fun save(layout: TerminalLayout) {
        settings.putString(KEY_DOCK, layout.dock.name)
        settings.putFloat(KEY_HEIGHT, layout.height)
        settings.putFloat(KEY_WIDTH, layout.width)
    }

    private companion object {
        const val KEY_DOCK = "terminal_dock"
        const val KEY_HEIGHT = "terminal_height"
        const val KEY_WIDTH = "terminal_width"
    }
}
