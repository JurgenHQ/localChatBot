package com.localchatbot.core.storage

/**
 * Almacén de la "memoria" del modelo (`memory.md`), hermano de `tools.md` y `skills/`
 * (`~/.localchatbot/memory.md` en desktop). Guarda **preferencias del usuario** que el
 * modelo aprende ("usa commits en inglés", "no incluyas firmas de IA", "prefiero TypeScript")
 * y debe respetar al ejecutar tareas (commits, naming, tono, idioma…).
 *
 * A diferencia de [ToolDocsStore] (read-only, curado por nosotros), memory.md es
 * **read-write por el modelo**: lo lee vía `read_memory` y le añade entradas vía
 * `save_memory`. Un resumen se inyecta siempre en el system prompt; el detalle completo
 * se consulta on-demand.
 *
 * Solo desktop tiene impl real; en móvil [isAvailable] es false.
 */
expect class MemoryStore {
    val isAvailable: Boolean

    /** Contenido completo de memory.md, o null si no existe / plataforma no soportada. */
    fun read(): String?

    /**
     * Añade [entry] como una nueva entrada (bullet) al final de memory.md, creando el
     * archivo con cabecera si no existía. Devuelve true si se escribió correctamente.
     */
    fun append(entry: String): Boolean
}

expect fun createMemoryStore(): MemoryStore

/** Cabecera sembrada la primera vez que se crea memory.md. */
const val MEMORY_HEADER: String =
    "# User Memory\n\n" +
        "Preferences the user has stated, to honor across tasks (commits, naming, tone, " +
        "language, tooling). One bullet per preference.\n"
