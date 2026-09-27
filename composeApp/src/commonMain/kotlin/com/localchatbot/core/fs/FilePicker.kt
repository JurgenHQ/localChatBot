package com.localchatbot.core.fs

import androidx.compose.runtime.Composable

/**
 * Archivo adjuntado por el usuario antes de enviar un mensaje. Se prepend
 * como bloque de código fenced en el texto del mensaje. El [content] ya es
 * texto plano: para PDF/DOCX se extrajo el texto en la capa de plataforma.
 */
data class AttachedTextFile(val name: String, val content: String)

/** Lanzador del selector de archivos, agnóstico de plataforma. */
interface FilePickerLauncher {
    fun launch()
}

/**
 * Devuelve un [FilePickerLauncher] recordado en la composición.
 *
 * Soporta texto plano/código sin conversión, y extrae texto de PDF/DOCX
 * en plataformas donde hay un extractor disponible (ver cada actual).
 * Si el usuario cancela, [onResult] no se llama. Si el archivo es de un
 * formato no soportado (p. ej. `.doc` legado) o falla la extracción,
 * se invoca [onError] con un mensaje para mostrar al usuario.
 */
@Composable
expect fun rememberFilePicker(
    onResult: (AttachedTextFile) -> Unit,
    onError: (String) -> Unit = {}
): FilePickerLauncher
