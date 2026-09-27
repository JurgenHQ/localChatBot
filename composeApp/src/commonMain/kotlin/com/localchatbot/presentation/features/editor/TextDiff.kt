package com.localchatbot.presentation.features.editor

/**
 * Diff de líneas basado en LCS. Devuelve texto con líneas prefijadas por
 * `"- "` (borradas) o `"+ "` (añadidas); las comunes se omiten.
 * Limitado a [MAX_LINES] líneas por lado para mantener O(n²) manejable.
 */
fun buildLineDiff(original: String, updated: String, maxLines: Int = 300): String {
    val origLines = original.lines()
    val updLines = updated.lines()

    val n = origLines.size.coerceAtMost(maxLines)
    val m = updLines.size.coerceAtMost(maxLines)
    val truncated = origLines.size > maxLines || updLines.size > maxLines

    // LCS DP (índices desde el final para facilitar backtrack hacia delante)
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (origLines[i] == updLines[j]) {
                1 + dp[i + 1][j + 1]
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    // Backtrack para construir el diff
    val sb = StringBuilder()
    var i = 0
    var j = 0
    var diffLines = 0

    while ((i < n || j < m) && diffLines < maxLines) {
        when {
            i < n && j < m && origLines[i] == updLines[j] -> {
                // línea común — no se muestra
                i++; j++
            }
            j < m && (i >= n || dp[i + 1][j] >= dp[i][j + 1]) -> {
                sb.appendLine("+ ${updLines[j]}")
                j++; diffLines++
            }
            else -> {
                sb.appendLine("- ${origLines[i]}")
                i++; diffLines++
            }
        }
    }

    // Líneas restantes si salimos por el límite de diffLines
    while (j < m) { sb.appendLine("+ ${updLines[j++]}") }
    while (i < n) { sb.appendLine("- ${origLines[i++]}") }

    if (truncated) sb.appendLine("… (archivo truncado a $maxLines líneas para el diff)")

    return sb.toString().trimEnd()
}
