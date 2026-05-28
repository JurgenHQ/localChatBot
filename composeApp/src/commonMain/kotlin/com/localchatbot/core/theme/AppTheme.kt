package com.localchatbot.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { System, Light, Dark }

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.System,
    accentSeed: Long = 0L,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val base = if (useDark) DarkColors else LightColors
    val scheme = if (accentSeed != 0L) {
        val accent = Color(accentSeed)
        base.copy(
            primary = accent,
            primaryContainer = if (useDark) accent.darken(0.15f) else accent,
            onPrimary = onColorFor(accent),
            onPrimaryContainer = onColorFor(accent)
        )
    } else base

    SystemBarsEffect(useDark = useDark)

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content
    )
}

private fun Color.darken(amount: Float): Color = Color(
    red = (red * (1f - amount)).coerceIn(0f, 1f),
    green = (green * (1f - amount)).coerceIn(0f, 1f),
    blue = (blue * (1f - amount)).coerceIn(0f, 1f),
    alpha = alpha
)

private fun onColorFor(bg: Color): Color {
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (luminance > 0.6f) Color(0xFF0F1115) else Color.White
}
