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
import java.io.File
import java.util.concurrent.TimeUnit

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
 * En **Windows** el globo del [TrayIcon] usa la API `Shell_NotifyIcon`, obsoleta y
 * suprimida por Windows 10/11 (se enruta al Centro de notificaciones atribuida a
 * `javaw.exe`, cuyos avisos suelen estar desactivados). Por eso en Windows emitimos un
 * **toast nativo WinRT** (`Windows.UI.Notifications`) vía PowerShell, bajo un
 * AppUserModelID propio ([WINDOWS_AUMID]). Para que el toast muestre el nombre y el
 * icono de la app (y no el AUMID crudo), Windows sin paquete MSIX exige un **acceso
 * directo en el menú inicio con la propiedad AppUserModelID** — el `DisplayName` del
 * registro no lo honra de forma fiable. Ese acceso directo se crea una sola vez al
 * arrancar (ver [ensureWindowsRegistration]); el shell lo indexa antes de que llegue la
 * primera notificación (que ocurre al terminar un turno de chat, segundos después).
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

    /** Registro del AppUserModelID de Windows (acceso directo); se intenta una sola vez. */
    private val winLock = Any()
    private var winRegisterTried = false

    init {
        // Da de alta el acceso directo del AUMID al arrancar (en un hilo daemon), para que
        // el shell lo haya indexado cuando llegue la primera notificación.
        if (isWindows) {
            Thread { runCatching { ensureWindowsRegistration() } }
                .apply { isDaemon = true; name = "win-toast-register" }
                .start()
        }
    }

    actual fun notify(title: String, body: String) {
        requestAttention()
        if (isWindows) {
            // El TrayIcon no es fiable en Windows 10/11: usamos toast nativo WinRT y,
            // si el proceso falla, caemos al globo clásico. En un hilo daemon porque
            // notify es fire-and-forget y esperamos al proceso para poder hacer fallback.
            Thread {
                ensureWindowsRegistration() // no-op si ya se registró al arrancar
                if (!showWindowsToast(title, body)) showViaShell(title, body)
            }.apply { isDaemon = true; name = "win-toast" }.start()
        } else {
            if (!showViaTray(title, body)) showViaShell(title, body)
        }
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

    /**
     * Crea (una sola vez) el acceso directo del menú inicio con la propiedad
     * AppUserModelID, para que los toasts se atribuyan al nombre e icono de la app. Corre
     * el script `win-toast-register.ps1` (recurso empaquetado) que hace el COM
     * `IShellLink`/`IPropertyStore`. Serializado bajo [winLock]: si un toast llega antes
     * de que termine el registro del arranque, espera aquí y luego notifica.
     *
     * Idempotente: si el `.lnk` ya existe (arranques posteriores), no hace nada.
     */
    private fun ensureWindowsRegistration() {
        synchronized(winLock) {
            if (winRegisterTried) return
            winRegisterTried = true
            runCatching {
                val home = System.getProperty("user.home") ?: return
                val dir = File(home, ".localchatbot").apply { mkdirs() }

                val startMenu = System.getenv("APPDATA")?.let {
                    File(it, "Microsoft\\Windows\\Start Menu\\Programs")
                } ?: return
                val link = File(startMenu, "$APP_NAME.lnk")
                if (link.exists()) return // ya registrado en un arranque anterior

                val ico = extractResource("AppIcon.ico", File(dir, "app-icon.ico")) ?: return
                val script = extractResource("win-toast-register.ps1", File(dir, "win-toast-register.ps1"))
                    ?: return

                val proc = ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", script,
                    "-AumId", WINDOWS_AUMID,
                    "-LinkName", APP_NAME,
                    "-Target", currentExecutable(),
                    "-IconPath", ico,
                ).redirectErrorStream(true).start()
                proc.inputStream.close()
                proc.waitFor(15, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Copia un recurso empaquetado a [dest] si no existe ya. Devuelve la ruta absoluta o
     * null si el recurso no está o falló la escritura.
     */
    private fun extractResource(resource: String, dest: File): String? = runCatching {
        if (!dest.exists() || dest.length() == 0L) {
            val bytes = this::class.java.classLoader
                ?.getResourceAsStream(resource)?.use { it.readBytes() } ?: return null
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        }
        dest.absolutePath
    }.getOrNull()

    /** Ejecutable actual: destino del acceso directo (app real instalada; `javaw` en dev). */
    private fun currentExecutable(): String =
        ProcessHandle.current().info().command().orElse(null)
            ?: (System.getProperty("java.home") + File.separator + "bin" + File.separator + "javaw.exe")

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

    /**
     * Toast nativo de Windows 10/11 vía WinRT (`Windows.UI.Notifications`) lanzado con
     * PowerShell. A diferencia del globo del [TrayIcon], aparece de forma fiable en el
     * Centro de notificaciones. El nombre y el icono los aporta el acceso directo del
     * AUMID (ver [ensureWindowsRegistration]). Espera al proceso (con timeout) y devuelve
     * false si no arrancó o terminó con error, para caer al globo clásico.
     */
    private fun showWindowsToast(title: String, body: String): Boolean = runCatching {
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            buildToastScript(title, body),
        ).redirectErrorStream(true).start()
        proc.inputStream.close()
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroy()
            return false
        }
        proc.exitValue() == 0
    }.getOrDefault(false)

    /**
     * Script PowerShell que muestra el toast bajo el AUMID de la app. Usa **solo comillas
     * simples** para que el quoting de [ProcessBuilder] en Windows no lo rompa; el
     * título/cuerpo se escapan como literales PowerShell y el XML lo construye
     * `CreateTextNode` (sin riesgo de inyección en el XML del toast).
     */
    private fun buildToastScript(title: String, body: String): String {
        val t = escapePowerShell(title)
        val b = escapePowerShell(body)
        return buildString {
            append("\$ErrorActionPreference='Stop';")
            // Construcción del toast vía WinRT (plantilla título en negrita + cuerpo).
            append("[Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,ContentType=WindowsRuntime]|Out-Null;")
            append("\$x=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);")
            append("\$n=\$x.GetElementsByTagName('text');")
            append("\$n.Item(0).AppendChild(\$x.CreateTextNode('$t'))|Out-Null;")
            append("\$n.Item(1).AppendChild(\$x.CreateTextNode('$b'))|Out-Null;")
            append("\$toast=[Windows.UI.Notifications.ToastNotification]::new(\$x);")
            append("[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$WINDOWS_AUMID').Show(\$toast)")
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

    private companion object {
        const val APP_NAME = "LocalChatBot"
        /** AppUserModelID registrado en HKCU para atribuir los toasts a la app. */
        const val WINDOWS_AUMID = "com.localchatbot.app"
    }
}
