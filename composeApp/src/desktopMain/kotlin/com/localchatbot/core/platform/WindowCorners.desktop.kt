package com.localchatbot.core.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: Memory, cbAttribute: Int): Int

    companion object {
        val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
    }
}

private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWCP_ROUND = 2

/**
 * Recupera el redondeo de esquinas de Windows 11 que se pierde al usar
 * `undecorated = true` (DWM solo lo aplica solo por defecto a ventanas con barra de
 * título nativa). Se pide vía JNA en vez de un proceso de PowerShell externo -como el
 * resto de tweaks nativos de esta app- porque una ventana `undecorated` no expone
 * `MainWindowHandle` (comprobado: siempre da 0), así que no hay forma de localizarla
 * desde fuera del proceso; JNA da el HWND directamente desde dentro de la JVM. No hace
 * nada en Windows &lt; 11 (el atributo no existe ahí; la llamada falla en silencio).
 */
fun applyWindowsRoundedCorners(window: Window) {
    runCatching {
        val hwnd = HWND(Native.getComponentPointer(window))
        val pref = Memory(4).apply { setInt(0, DWMWCP_ROUND) }
        Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, pref, 4)
    }
}
