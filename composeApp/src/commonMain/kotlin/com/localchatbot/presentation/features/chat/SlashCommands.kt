package com.localchatbot.presentation.features.chat

/**
 * Comandos del composer: se escriben con `/` y actúan sobre la conversación, no sobre el
 * modelo (no se envían como mensaje).
 *
 * Conviven en el mismo popup con las skills, que también se invocan con `/`. La diferencia
 * es qué hacen: una skill **modifica el próximo mensaje** (inyecta su system prompt), un
 * comando **ejecuta una acción de la app** y no manda nada al modelo.
 *
 * Fuente única para las dos rutas que los disparan — elegirlo del popup y escribirlo a mano
 * + Enter — para que no se desincronicen.
 */
enum class SlashCommand(
    val id: String,
    val description: String,
    /** Alias aceptados al escribirlo a mano. El [id] es el que se muestra en el popup. */
    val aliases: List<String> = emptyList()
) {
    Compact(
        id = "compact",
        description = "Resumir el historial viejo para liberar contexto",
        aliases = listOf("compactar")
    ),
    UndoCompact(
        id = "uncompact",
        description = "Deshacer la compactación: volver a enviar todo el historial",
        aliases = listOf("descompactar")
    ),
    Export(
        id = "export",
        description = "Copiar la conversación como Markdown",
        aliases = listOf("exportar")
    ),
    NewSession(
        id = "new",
        description = "Empezar una conversación nueva",
        aliases = listOf("nueva")
    ),
    Init(
        id = "init",
        description = "Generar AGENTS.md investigando el workspace",
        aliases = listOf("iniciar")
    );

    /** Texto tal como se escribe. */
    val token: String get() = "/$id"

    companion object {
        /**
         * Resuelve el texto completo del composer a un comando, o null si no lo es.
         *
         * Compara por **igualdad exacta** (ignorando espacios y mayúsculas), no por prefijo:
         * `/` también abre el selector de skills, y un `startsWith` se comería invocaciones
         * legítimas como `/caveman ultra`.
         */
        fun parse(raw: String): SlashCommand? {
            val text = raw.trim().removePrefix("/").lowercase()
            if (text.isEmpty()) return null
            return entries.firstOrNull { cmd ->
                cmd.id == text || cmd.aliases.any { it == text }
            }
        }

        /**
         * Comandos ofrecibles en el estado actual. Un comando que no puede hacer nada no se
         * lista: ofrecer "compactar" sin conversación, o "descompactar" sin compactación,
         * solo genera un error que el usuario no pidió.
         */
        fun availableFor(
            hasMessages: Boolean,
            compacted: Boolean,
            initAvailable: Boolean = false
        ): List<SlashCommand> =
            entries.filter { cmd ->
                when (cmd) {
                    NewSession -> true
                    UndoCompact -> compacted
                    Compact, Export -> hasMessages
                    // El resto de las condiciones (modo Build, archivo ya existente, sin
                    // conexión) no se filtran acá: se muestran como error dentro del
                    // diálogo, igual que MIN_MESSAGES en Compact.
                    Init -> initAvailable
                }
            }
    }
}
