package com.localchatbot.presentation.features.editor

enum class SyntaxType { Keyword, StringLiteral, Comment, Number, Annotation, Tag, Key }

data class SyntaxSpan(val start: Int, val end: Int, val type: SyntaxType)

/**
 * Resaltado sintáctico mínimo basado en regex para el editor embebido.
 * Solo se activa en archivos de hasta [MAX_CHARS] caracteres para no bloquear la UI.
 * El tokenizador es greedy "primera coincidencia gana" con desempate por orden de
 * definición de los patrones (comentarios > cadenas > anotaciones > palabras clave > números).
 */
object SyntaxHighlighter {

    private const val MAX_CHARS = 12_000

    fun highlight(text: String, extension: String): List<SyntaxSpan> {
        if (text.length > MAX_CHARS) return emptyList()
        val patterns = patternsFor(extension) ?: return emptyList()
        return tokenize(text, patterns)
    }

    private fun tokenize(text: String, patterns: List<Pair<Regex, SyntaxType>>): List<SyntaxSpan> {
        val result = mutableListOf<SyntaxSpan>()
        var pos = 0
        while (pos < text.length) {
            var bestStart = Int.MAX_VALUE
            var bestEnd = Int.MAX_VALUE
            var bestType = SyntaxType.Keyword
            var bestPriority = Int.MAX_VALUE
            patterns.forEachIndexed { i, (regex, type) ->
                val m = regex.find(text, pos) ?: return@forEachIndexed
                val s = m.range.first
                if (s < bestStart || (s == bestStart && i < bestPriority)) {
                    bestStart = s
                    bestEnd = m.range.last + 1
                    bestType = type
                    bestPriority = i
                }
            }
            if (bestStart == Int.MAX_VALUE) break
            result.add(SyntaxSpan(bestStart, bestEnd, bestType))
            pos = bestEnd.coerceAtLeast(pos + 1)
        }
        return result
    }

    fun patternsFor(extension: String): List<Pair<Regex, SyntaxType>>? =
        when (extension.lowercase().trimStart('.')) {
            "kt", "kts", "gradle" -> kotlinPatterns
            "java" -> javaPatterns
            "py" -> pythonPatterns
            "js", "ts", "jsx", "tsx", "mjs", "cjs" -> jsPatterns
            "json" -> jsonPatterns
            "sh", "bash", "zsh", "fish" -> shellPatterns
            "md", "markdown" -> markdownPatterns
            "xml", "html", "htm", "svg" -> xmlPatterns
            "yaml", "yml" -> yamlPatterns
            "toml" -> tomlPatterns
            "sql" -> sqlPatterns
            else -> null
        }

    // ── Kotlin / Groovy ──────────────────────────────────────────────────────

    private val KT_KW = listOf(
        "fun", "val", "var", "class", "object", "interface", "abstract", "override",
        "open", "final", "sealed", "data", "enum", "companion", "import", "package",
        "return", "if", "else", "when", "while", "for", "do", "in", "is", "as",
        "try", "catch", "finally", "throw", "true", "false", "null", "this", "super",
        "by", "init", "constructor", "private", "protected", "public", "internal",
        "inline", "suspend", "operator", "infix", "crossinline", "noinline",
        "lateinit", "typealias", "reified", "expect", "actual", "external",
        "Unit", "Int", "Long", "Float", "Double", "Boolean", "String", "Any",
        "List", "Map", "Set", "Pair", "Triple"
    )

    private val kotlinPatterns = listOf(
        Regex("""//[^\n]*""") to SyntaxType.Comment,
        Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.Comment,
        Regex("\"\"\".*?\"\"\"", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.StringLiteral,
        Regex(""""[^"\\\n]*(?:\\.[^"\\\n]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^'\\\n]*(?:\\.[^'\\\n]*)*'""") to SyntaxType.StringLiteral,
        Regex("""@\w+""") to SyntaxType.Annotation,
        Regex("""\b(${KT_KW.joinToString("|")})\b""") to SyntaxType.Keyword,
        Regex("""\b0x[\da-fA-F]+[LuU]*\b""") to SyntaxType.Number,
        Regex("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[fFLlUu]?\b""") to SyntaxType.Number,
    )

    // ── Java ─────────────────────────────────────────────────────────────────

    private val JAVA_KW = listOf(
        "public", "private", "protected", "static", "final", "abstract", "synchronized",
        "volatile", "transient", "native", "strictfp", "class", "interface", "enum",
        "extends", "implements", "import", "package", "return", "if", "else", "for",
        "while", "do", "switch", "case", "break", "continue", "default", "try",
        "catch", "finally", "throw", "throws", "new", "this", "super", "null",
        "true", "false", "void", "int", "long", "float", "double", "boolean",
        "byte", "short", "char", "String", "Object"
    )

    private val javaPatterns = listOf(
        Regex("""//[^\n]*""") to SyntaxType.Comment,
        Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.Comment,
        Regex(""""[^"\\\n]*(?:\\.[^"\\\n]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^'\\\n]*(?:\\.[^'\\\n]*)*'""") to SyntaxType.StringLiteral,
        Regex("""@\w+""") to SyntaxType.Annotation,
        Regex("""\b(${JAVA_KW.joinToString("|")})\b""") to SyntaxType.Keyword,
        Regex("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[fFLlUu]?\b""") to SyntaxType.Number,
    )

    // ── Python ───────────────────────────────────────────────────────────────

    private val PY_KW = listOf(
        "def", "class", "import", "from", "as", "return", "if", "elif", "else",
        "for", "while", "break", "continue", "pass", "try", "except", "finally",
        "raise", "with", "lambda", "yield", "and", "or", "not", "in", "is",
        "True", "False", "None", "global", "nonlocal", "del", "assert", "async", "await"
    )

    private val pythonPatterns = listOf(
        Regex("""#[^\n]*""") to SyntaxType.Comment,
        Regex("""\"\"\".*?\"\"\"""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.StringLiteral,
        Regex("""\'\'\'.*?\'\'\'""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.StringLiteral,
        Regex(""""[^"\\\n]*(?:\\.[^"\\\n]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^'\\\n]*(?:\\.[^'\\\n]*)*'""") to SyntaxType.StringLiteral,
        Regex("""@\w+""") to SyntaxType.Annotation,
        Regex("""\b(${PY_KW.joinToString("|")})\b""") to SyntaxType.Keyword,
        Regex("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[jJ]?\b""") to SyntaxType.Number,
    )

    // ── JavaScript / TypeScript ───────────────────────────────────────────────

    private val JS_KW = listOf(
        "function", "const", "let", "var", "class", "extends", "import", "export",
        "default", "return", "if", "else", "for", "while", "do", "switch", "case",
        "break", "continue", "try", "catch", "finally", "throw", "new", "this",
        "super", "null", "undefined", "true", "false", "typeof", "instanceof",
        "in", "of", "async", "await", "yield", "delete", "void",
        // TypeScript
        "interface", "type", "enum", "implements", "abstract", "readonly",
        "public", "private", "protected", "static", "declare", "namespace",
        "number", "string", "boolean", "any", "never", "unknown", "object"
    )

    private val jsPatterns = listOf(
        Regex("""//[^\n]*""") to SyntaxType.Comment,
        Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.Comment,
        Regex("""`[^`\\]*(?:\\.[^`\\]*)*`""") to SyntaxType.StringLiteral,
        Regex(""""[^"\\\n]*(?:\\.[^"\\\n]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^'\\\n]*(?:\\.[^'\\\n]*)*'""") to SyntaxType.StringLiteral,
        Regex("""@\w+""") to SyntaxType.Annotation,
        Regex("""\b(${JS_KW.joinToString("|")})\b""") to SyntaxType.Keyword,
        Regex("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""") to SyntaxType.Number,
    )

    // ── JSON ─────────────────────────────────────────────────────────────────

    private val jsonPatterns = listOf(
        // JSON key (string followed by :)
        Regex(""""[^"\\]*(?:\\.[^"\\]*)?"(?=\s*:)""") to SyntaxType.Key,
        // String value
        Regex(""""[^"\\]*(?:\\.[^"\\]*)*"""") to SyntaxType.StringLiteral,
        // Keywords
        Regex("""\b(true|false|null)\b""") to SyntaxType.Keyword,
        // Numbers
        Regex("""-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""") to SyntaxType.Number,
    )

    // ── Shell ─────────────────────────────────────────────────────────────────

    private val SH_KW = listOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
        "case", "esac", "function", "return", "exit", "echo", "local",
        "export", "unset", "readonly", "source", "alias", "true", "false"
    )

    private val shellPatterns = listOf(
        Regex("""#[^\n]*""") to SyntaxType.Comment,
        Regex(""""[^"\\\n]*(?:\\.[^"\\\n]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^']*'""") to SyntaxType.StringLiteral,
        Regex("""\b(${SH_KW.joinToString("|")})\b""") to SyntaxType.Keyword,
        Regex("""\$\w+""") to SyntaxType.Annotation,
        Regex("""\b\d+\b""") to SyntaxType.Number,
    )

    // ── Markdown ──────────────────────────────────────────────────────────────

    private val markdownPatterns = listOf(
        // Fenced code block
        Regex("""```.*?```""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.StringLiteral,
        // Inline code
        Regex("""`[^`]+`""") to SyntaxType.StringLiteral,
        // Headers
        Regex("""^#{1,6} .+""", RegexOption.MULTILINE) to SyntaxType.Keyword,
        // Bold
        Regex("""\*\*[^*]+\*\*""") to SyntaxType.Annotation,
        // Links
        Regex("""\[.*?\]\(.*?\)""") to SyntaxType.Tag,
    )

    // ── XML / HTML ────────────────────────────────────────────────────────────

    private val xmlPatterns = listOf(
        Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.Comment,
        Regex(""""[^"]*"""") to SyntaxType.StringLiteral,
        Regex("""'[^']*'""") to SyntaxType.StringLiteral,
        // Closing tag
        Regex("""</[\w:.-]+>""") to SyntaxType.Tag,
        // Opening/self-closing tag name
        Regex("""<[\w:.-]+""") to SyntaxType.Tag,
        // Attribute names
        Regex("""\b[\w:-]+=(?=["'])""") to SyntaxType.Key,
    )

    // ── YAML ─────────────────────────────────────────────────────────────────

    private val yamlPatterns = listOf(
        Regex("""#[^\n]*""") to SyntaxType.Comment,
        Regex(""""[^"\\]*(?:\\.[^"\\]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^']*'""") to SyntaxType.StringLiteral,
        // Key (word followed by colon)
        Regex("""^\s*[\w.-]+(?=\s*:)""", RegexOption.MULTILINE) to SyntaxType.Key,
        Regex("""\b(true|false|null|yes|no|on|off)\b""") to SyntaxType.Keyword,
        Regex("""\b-?\d+(?:\.\d+)?\b""") to SyntaxType.Number,
    )

    // ── TOML ─────────────────────────────────────────────────────────────────

    private val tomlPatterns = listOf(
        Regex("""#[^\n]*""") to SyntaxType.Comment,
        Regex(""""[^"\\]*(?:\\.[^"\\]*)*"""") to SyntaxType.StringLiteral,
        Regex("""'[^']*'""") to SyntaxType.StringLiteral,
        // Table header
        Regex("""^\[.*?\]""", RegexOption.MULTILINE) to SyntaxType.Annotation,
        // Key = value
        Regex("""^\s*[\w.-]+(?=\s*=)""", RegexOption.MULTILINE) to SyntaxType.Key,
        Regex("""\b(true|false)\b""") to SyntaxType.Keyword,
        Regex("""\b-?\d+(?:\.\d+)?\b""") to SyntaxType.Number,
    )

    // ── SQL ──────────────────────────────────────────────────────────────────

    private val SQL_KW = listOf(
        "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
        "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "DROP",
        "TABLE", "INDEX", "VIEW", "CONSTRAINT", "PRIMARY", "KEY", "FOREIGN",
        "REFERENCES", "NOT", "NULL", "DEFAULT", "UNIQUE", "AND", "OR", "IN",
        "EXISTS", "BETWEEN", "LIKE", "IS", "DISTINCT", "ALL", "UNION",
        // lowercase
        "select", "from", "where", "join", "left", "right", "inner", "outer",
        "on", "group", "by", "order", "having", "limit", "offset", "as",
        "insert", "into", "values", "update", "set", "delete", "create", "drop",
        "table", "index", "view", "not", "null", "default", "unique", "and", "or"
    )

    private val sqlPatterns = listOf(
        Regex("""--[^\n]*""") to SyntaxType.Comment,
        Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)) to SyntaxType.Comment,
        Regex("""'[^']*'""") to SyntaxType.StringLiteral,
        Regex("""\b(${SQL_KW.distinct().joinToString("|")})\b""") to SyntaxType.Keyword,
        Regex("""\b\d+(?:\.\d+)?\b""") to SyntaxType.Number,
    )
}
