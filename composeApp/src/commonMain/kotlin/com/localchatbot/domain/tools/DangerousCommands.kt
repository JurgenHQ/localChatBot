package com.localchatbot.domain.tools

/**
 * Denylist de patrones de comandos shell destructivos. No bloquea la ejecución:
 * cuando un comando matchea, `run_command` fuerza el diálogo de confirmación
 * aunque YOLO mode esté activo. El usuario sigue pudiendo aprobar usos
 * legítimos (p. ej. un `sudo apt install` consciente).
 *
 * La detección es best-effort sobre el texto del comando — un atacante puede
 * ofuscar (variables, base64, scripts), pero el objetivo es parar el caso real:
 * un modelo local que alucina un comando destructivo en YOLO mode.
 */
internal object DangerousCommands {

    private data class Rule(val pattern: Regex, val reason: String)

    private val rules = listOf(
        // Solo matchea la raíz o ~ a secas (o con wildcard): `rm -rf /`, `rm -rf ~`,
        // `rm -rf /*`. Borrar subdirectorios (`rm -rf /tmp/foo`, `rm -rf ~/old`) es
        // uso normal y NO debe forzar confirmación.
        Rule(
            Regex("""\brm\s+(-\w*[rf]\w*\s+)+(-\w+\s+)*"?(/|~)/?\s*("|\*|$|[;&|])""", RegexOption.IGNORE_CASE),
            "rm recursivo/forzado sobre / o ~"
        ),
        Rule(Regex("""(^|[;&|]\s*)(sudo|doas)\s""", RegexOption.IGNORE_CASE), "escalada de privilegios (sudo/doas)"),
        Rule(Regex(""":\s*\(\s*\)\s*\{.*:\s*\|\s*:"""), "fork bomb"),
        Rule(Regex("""\bmkfs(\.\w+)?\b""", RegexOption.IGNORE_CASE), "formateo de filesystem (mkfs)"),
        Rule(Regex("""\bdd\b[^;|&]*\bof=/dev/""", RegexOption.IGNORE_CASE), "escritura directa a dispositivo (dd of=/dev/…)"),
        Rule(Regex(""">\s*/dev/(sd|disk|nvme|hd)""", RegexOption.IGNORE_CASE), "redirección a dispositivo de bloque"),
        Rule(Regex("""(^|[;&|]\s*)(shutdown|reboot|halt|poweroff)\b""", RegexOption.IGNORE_CASE), "apagado/reinicio del sistema"),
        Rule(Regex("""\bchmod\s+(-\w+\s+)*[0-7]*777\s+/(\s|$)"""), "chmod 777 sobre la raíz"),
        Rule(Regex("""\bkill(all)?\s+(-9\s+)?-1\b"""), "kill de todos los procesos (kill -1)"),
        Rule(Regex("""\bdiskutil\s+(erase|partition)""", RegexOption.IGNORE_CASE), "borrado de disco (diskutil)"),
        Rule(Regex("""(^|[;&|]\s*)format\s+[a-z]:""", RegexOption.IGNORE_CASE), "formateo de unidad (Windows format)")
    )

    /** Devuelve el motivo si [command] matchea algún patrón peligroso, o null. */
    fun match(command: String): String? =
        rules.firstOrNull { it.pattern.containsMatchIn(command) }?.reason
}
