package com.localchatbot.core.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Base64
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
actual fun rememberDirectoryPicker(onResult: (String) -> Unit): DirectoryPickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(scope, onResult) { DesktopDirectoryPicker(scope, onResult) }
}

private class DesktopDirectoryPicker(
    private val scope: CoroutineScope,
    private val onResult: (String) -> Unit
) : DirectoryPickerLauncher {
    override fun launch() {
        scope.launch {
            val dir = chooseDirectory() ?: return@launch
            onResult(dir.absolutePath)
        }
    }

    private suspend fun chooseDirectory(): File? {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (isWindows) {
            // Diálogo real del Explorador de Windows (no el JFileChooser con look Java):
            // se delega a PowerShell/WinForms, que sí expone el selector de carpetas
            // moderno. Corre fuera del EDT porque lanza un proceso bloqueante.
            return withContext(Dispatchers.IO) {
                val outcome = chooseDirectoryWindowsNative()
                // Solo se cae al JFileChooser si PowerShell falló al arrancar/ejecutar
                // (outcome == null). Si el usuario canceló el diálogo nativo, `outcome`
                // es un Result exitoso con File=null y NO debe abrir un segundo picker.
                if (outcome != null) outcome.getOrNull() else chooseDirectoryFallback(isMac = false)
            }
        }
        return chooseDirectoryFallback(isMac)
    }

    /**
     * Abre el picker de carpetas nativo de Windows shelleando a PowerShell con
     * System.Windows.Forms.FolderBrowserDialog. Se probó antes con el "truco" de
     * OpenFileDialog en modo carpeta (ValidateNames/CheckFileExists en false), pero
     * ese diálogo es un selector de ARCHIVOS: un click normal sobre la carpeta
     * deseada y "Abrir" simplemente navega dentro de ella en vez de seleccionarla,
     * así que para el usuario "no pasaba nada" al elegir la carpeta. FolderBrowserDialog
     * está pensado para carpetas: un click la resalta, y OK la confirma sin ambigüedad.
     * El script va en -EncodedCommand (Base64 UTF-16LE) para no depender de escapar
     * comillas al pasarlo por ProcessBuilder.
     *
     * La ruta se marca con el sentinel [PATH_SENTINEL] y se extrae por ese prefijo, NO
     * tomando "la última línea": PowerShell puede emitir ruido CLIXML del stream de
     * progreso ("Preparando módulos para el primer uso") al cargar WinForms, que con
     * redirectErrorStream cae en stdout y quedaría DESPUÉS de la ruta — tomar la última
     * línea agarraba ese XML en vez del path y todo parecía "cancelado". Se silencia el
     * progreso con ${'$'}ProgressPreference y se deja stderr aparte por si acaso.
     *
     * Devuelve null si PowerShell no está disponible o algo falla al lanzarlo (el
     * caller cae a [chooseDirectoryFallback] en ese caso); un Result no-null con
     * payload null significa "el usuario canceló", que NO debe disparar el fallback.
     */
    private fun chooseDirectoryWindowsNative(): Result<File?>? = try {
        val script = """
            ${'$'}ProgressPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Windows.Forms
            ${'$'}d = New-Object System.Windows.Forms.FolderBrowserDialog
            ${'$'}d.Description = "Elegir workspace"
            ${'$'}d.ShowNewFolderButton = ${'$'}true
            if (${'$'}d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                Write-Output "$PATH_SENTINEL${'$'}(${'$'}d.SelectedPath)"
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded
        ).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val dir = output.lineSequence()
            .firstOrNull { it.startsWith(PATH_SENTINEL) }
            ?.removePrefix(PATH_SENTINEL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
        Result.success(dir)
    } catch (_: Exception) {
        null
    }

    private suspend fun chooseDirectoryFallback(isMac: Boolean): File? {
        val deferred = CompletableDeferred<File?>()
        SwingUtilities.invokeLater {
            val result = if (isMac) {
                // FileDialog nativo: solo macOS soporta selección de carpetas por esta vía.
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                val dialog = FileDialog(null as Frame?, "Elegir workspace", FileDialog.LOAD)
                dialog.isVisible = true
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                if (dialog.file != null) File(dialog.directory, dialog.file) else null
            } else {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Elegir workspace"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile
                } else null
            }
            deferred.complete(result)
        }
        return deferred.await()
    }

    private companion object {
        // Prefijo con el que el script de PowerShell marca la ruta elegida, para
        // extraerla sin confundirla con ruido CLIXML u otras líneas de stdout.
        const val PATH_SENTINEL = "LCB_PATH::"
    }
}
