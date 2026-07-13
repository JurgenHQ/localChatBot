package com.localchatbot.core.platform

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.TrayIcon

/**
 * Implementación de escritorio: hace rebotar el icono del dock/taskbar y muestra una
 * notificación nativa.
 *
 * La notificación se emite vía [SystemTray]/[TrayIcon] para que macOS/Windows la
 * atribuyan a **la propia app** (no al "Editor de Scripts", como pasaba con
 * `osascript`) y para que al hacer **clic** se traiga LocalChatBot al frente. Esto
 * añade un pequeño icono en la barra de menú / bandeja del sistema (con menú
 * "Abrir" / "Salir"), que se crea de forma perezosa la primera vez que se notifica.
 *
 * Si la bandeja del sistema no está disponible (algunos entornos Linux), se cae al
 * método por proceso: `osascript` (macOS) / `notify-send` (Linux) / PowerShell.
 */
actual class SystemNotifier actual constructor() {

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val isMac = os.contains("mac")
    private val isWindows = os.contains("win")

    @Volatile
    private var trayIcon: TrayIcon? = null
    private var trayInitTried = false
    private val lock = Any()

    actual fun notify(title: String, body: String) {
        requestAttention()
        if (!showViaTray(title, body)) showViaShell(title, body)
    }

    /** Rebote del dock (macOS) / parpadeo de la barra de tareas (Windows). Best-effort. */
    private fun requestAttention() {
        runCatching {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
                // enabled = true, critical = false → un rebote, no continuo.
                taskbar.requestUserAttention(true, false)
            }
        }
    }

    /**
     * Muestra la notificación con un [TrayIcon] propio de la app. Devuelve false si la
     * bandeja no está soportada o no se pudo crear el icono (para caer al fallback).
     */
    private fun showViaTray(title: String, body: String): Boolean {
        if (!SystemTray.isSupported()) return false
        val icon = ensureTrayIcon() ?: return false
        return runCatching {
            EventQueue.invokeLater {
                runCatching { icon.displayMessage(title, body, TrayIcon.MessageType.INFO) }
            }
            true
        }.getOrDefault(false)
    }

    /** Crea (una sola vez) el icono de bandeja y lo registra. Null si no se pudo. */
    private fun ensureTrayIcon(): TrayIcon? {
        synchronized(lock) {
            trayIcon?.let { return it }
            if (trayInitTried) return null
            trayInitTried = true
            return runCatching {
                val image = loadTrayImage() ?: return null
                val popup = PopupMenu().apply {
                    add(MenuItem("Abrir LocalChatBot").apply { addActionListener { bringAppToFront() } })
                    add(MenuItem("Salir").apply { addActionListener { System.exit(0) } })
                }
                val icon = TrayIcon(image, "LocalChatBot", popup).apply {
                    isImageAutoSize = true
                    // Clic en la notificación / en el icono → traer la app al frente.
                    addActionListener { bringAppToFront() }
                }
                SystemTray.getSystemTray().add(icon)
                trayIcon = icon
                icon
            }.getOrNull()
        }
    }

    /**
     * Icono para la bandeja: reutiliza el AppIcon.png empaquetado en resources. Se lee
     * de forma síncrona (ImageIO) para que el icono no aparezca en blanco por la carga
     * diferida de Toolkit.getImage; si falla, cae a esa carga asíncrona.
     */
    private fun loadTrayImage(): Image? = runCatching {
        val url = this::class.java.classLoader?.getResource("AppIcon.png") ?: return null
        javax.imageio.ImageIO.read(url) ?: Toolkit.getDefaultToolkit().getImage(url)
    }.getOrNull()

    /** Trae la ventana principal al frente (y la des-minimiza si hacía falta). */
    private fun bringAppToFront() {
        runCatching {
            if (isMac) {
                // Activa la app aunque esté en segundo plano (equivalente a NSApp activate).
                val appClass = Class.forName("com.apple.eawt.Application")
                val app = appClass.getMethod("getApplication").invoke(null)
                runCatching {
                    appClass.getMethod("requestForeground", Boolean::class.javaPrimitiveType)
                        .invoke(app, true)
                }
            }
            Frame.getFrames().forEach { f ->
                if (f.isDisplayable) {
                    if (f.state == Frame.ICONIFIED) f.state = Frame.NORMAL
                    f.isVisible = true
                    f.toFront()
                    f.requestFocus()
                }
            }
        }
    }

    /** Fallback por proceso cuando no hay bandeja del sistema (p.ej. algunos Linux). */
    private fun showViaShell(title: String, body: String) {
        runCatching {
            when {
                isMac -> {
                    val script =
                        "display notification \"${escapeAppleScript(body)}\" " +
                            "with title \"${escapeAppleScript(title)}\""
                    ProcessBuilder("osascript", "-e", script).start()
                }
                isWindows -> {
                    val script = buildString {
                        append("Add-Type -AssemblyName System.Windows.Forms;")
                        append("Add-Type -AssemblyName System.Drawing;")
                        append("\$n = New-Object System.Windows.Forms.NotifyIcon;")
                        append("\$n.Icon = [System.Drawing.SystemIcons]::Information;")
                        append("\$n.Visible = \$true;")
                        append("\$n.ShowBalloonTip(5000, '${escapePowerShell(title)}', '${escapePowerShell(body)}', 'Info');")
                        append("Start-Sleep -Seconds 6; \$n.Dispose()")
                    }
                    ProcessBuilder("powershell", "-NoProfile", "-Command", script).start()
                }
                else -> ProcessBuilder("notify-send", title, body).start()
            }
        }
    }

    /** Escapa `\` y `"` para incrustar texto dentro de un literal de AppleScript. */
    private fun escapeAppleScript(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** En PowerShell las comillas simples se escapan duplicándolas. */
    private fun escapePowerShell(s: String): String = s.replace("'", "''")
}
