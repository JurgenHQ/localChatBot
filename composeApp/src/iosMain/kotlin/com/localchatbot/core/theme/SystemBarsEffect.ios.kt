package com.localchatbot.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle

/**
 * Sin esto, la status bar y el home indicator siguen el modo claro/oscuro
 * DEL SISTEMA, no el tema elegido dentro de la app — si el usuario fuerza
 * "Claro" en la app mientras el teléfono está en modo oscuro (o viceversa),
 * quedan con colores que no combinan con el resto de la UI.
 */
@Composable
actual fun SystemBarsEffect(useDark: Boolean) {
    SideEffect {
        val style = if (useDark) UIUserInterfaceStyle.UIUserInterfaceStyleDark
        else UIUserInterfaceStyle.UIUserInterfaceStyleLight
        UIApplication.sharedApplication.keyWindow?.overrideUserInterfaceStyle = style
    }
}
