package com.localchatbot.core.text

/**
 * Limpieza de salida de consola, compartida por todo lo que captura stdout/stderr de un
 * proceso: las tools de shell ([com.localchatbot.core.fs.FilesystemAgent]) y la terminal
 * integrada ([com.localchatbot.core.terminal.ShellSession]).
 *
 * Vive en commonMain aunque solo desktop ejecute procesos, para que el filtrado sea uno
 * y no dos regex que se van desincronizando.
 */
object ConsoleText {

    /**
     * Secuencias de escape ANSI: OSC (`ESC ] … BEL` o `ESC ] … ESC \`), CSI (`ESC [ … m`)
     * y escapes de dos caracteres (`ESC c`).
     *
     * **El orden de las alternativas importa.** La clase de la última incluye el rango
     * `\`–`_`, y ahí cae `]`: si fuera primera, `ESC ]` casaría como escape de dos
     * caracteres y dejaría suelto el cuerpo del OSC. Eso es exactamente lo que hacía antes
     * y por lo que la salida de los comandos llegaba con restos tipo `697;OSCLock=` — los
     * emite cualquier shell con integración de terminal (starship, fig, iTerm2), y se los
     * comía tanto la terminal integrada como el resultado que ve el modelo.
     */
    private val ANSI_PATTERN =
        Regex("""\x1B(?:\][^\x07\x1B]*(?:\x07|\x1B\\)|\[[0-?]*[ -/]*[@-~]|[@-Z\\-_])""")

    /** Caracteres de control ilegales en XML 1.0 (excepto \t \n \r). */
    private val CONTROL_CHARS_PATTERN = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F￾￿]""")

    /**
     * Elimina códigos ANSI (colores, movimientos de cursor) y caracteres de control.
     *
     * El motivo original es de persistencia, no estético: `PropertiesSettings` guarda con
     * `storeToXML()` y un char de control produce XML malformado → la escritura falla en
     * silencio y la sesión no se guarda. Para la terminal integrada, además, sin esto se
     * verían los escapes crudos que emiten los prompts con integraciones (starship, fig…).
     */
    fun sanitize(text: String): String =
        ANSI_PATTERN.replace(text, "").replace(CONTROL_CHARS_PATTERN, "")
}
