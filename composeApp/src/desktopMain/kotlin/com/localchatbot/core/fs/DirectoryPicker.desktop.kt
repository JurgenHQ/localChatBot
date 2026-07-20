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
     * Abre el picker de carpetas nativo de Windows shelleando a PowerShell con el
     * "truco" de System.Windows.Forms.OpenFileDialog en modo carpeta (ValidateNames/
     * CheckFileExists en false): es el mismo diálogo Explorer moderno, no el viejo
     * FolderBrowserDialog en árbol. El script va en -EncodedCommand (Base64 UTF-16LE)
     * para no depender de escapar comillas al pasarlo por ProcessBuilder.
     * Devuelve null si PowerShell no está disponible o algo falla al lanzarlo (el
     * caller cae a [chooseDirectoryFallback] en ese caso); un Result no-null con
     * payload null significa "el usuario canceló", que NO debe disparar el fallback.
     */
    private fun chooseDirectoryWindowsNative(): Result<File?>? = try {
        val script = """
            Add-Type -AssemblyName System.Windows.Forms
            ${'$'}d = New-Object System.Windows.Forms.OpenFileDialog
            ${'$'}d.ValidateNames = ${'$'}false
            ${'$'}d.CheckFileExists = ${'$'}false
            ${'$'}d.CheckPathExists = ${'$'}true
            ${'$'}d.FileName = "Selecciona esta carpeta"
            ${'$'}d.Title = "Elegir workspace"
            if (${'$'}d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                Write-Output ([System.IO.Path]::GetDirectoryName(${'$'}d.FileName))
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        val dir = output.lines().lastOrNull { it.isNotBlank() }
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
}
