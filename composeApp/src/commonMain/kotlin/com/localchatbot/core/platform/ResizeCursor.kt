package com.localchatbot.core.platform

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Cursor de redimensionado para las asas de los paneles acoplados. Compose común no trae
 * cursores de resize, así que en desktop se usa el de AWT; en móvil no hay puntero y se
 * devuelve el cursor por defecto.
 *
 * @param horizontal true para arrastrar en horizontal (↔), false para vertical (↕).
 */
expect fun resizeCursor(horizontal: Boolean): PointerIcon
