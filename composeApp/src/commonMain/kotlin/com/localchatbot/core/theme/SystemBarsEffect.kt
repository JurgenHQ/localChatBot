package com.localchatbot.core.theme

import androidx.compose.runtime.Composable

/**
 * Sincroniza la apariencia de las barras del sistema (iconos de status bar y
 * navigation bar) con el tema activo de la app.
 *
 * - En Android actualiza la apariencia de la ventana para que los iconos sean
 *   claros (modo oscuro) u oscuros (modo claro) y las barras sean transparentes.
 * - En iOS es un no-op; el sistema lo gestiona automáticamente.
 */
@Composable
expect fun SystemBarsEffect(useDark: Boolean)
