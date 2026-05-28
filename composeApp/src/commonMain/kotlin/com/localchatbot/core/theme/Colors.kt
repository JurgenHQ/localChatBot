package com.localchatbot.core.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val Cream = Color(0xFFF5F2EC)
internal val CreamSurface = Color(0xFFFFFFFF)
internal val CreamSurfaceAlt = Color(0xFFEFEBE2)
internal val InkBlack = Color(0xFF0F1115)
internal val InkSecondary = Color(0xFF6B6F76)
internal val InkTertiary = Color(0xFF9AA0A8)
internal val Divider = Color(0xFFE6E2D8)

internal val AccentBlue = Color(0xFF2C5AFF)
internal val AccentBlueDark = Color(0xFF1E46DB)

internal val SuccessGreen = Color(0xFF2EBD66)
internal val DestructiveRed = Color(0xFFE84A4A)

internal val DarkBg = Color(0xFF0F1115)
internal val DarkSurface = Color(0xFF181A20)
internal val DarkSurfaceAlt = Color(0xFF22252D)
internal val DarkDivider = Color(0xFF2A2D35)
internal val DarkInk = Color(0xFFF2F2F5)
internal val DarkInkSecondary = Color(0xFFA8ADB7)

val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlue,
    onPrimaryContainer = Color.White,
    secondary = InkSecondary,
    onSecondary = Color.White,
    background = Cream,
    onBackground = InkBlack,
    surface = CreamSurface,
    onSurface = InkBlack,
    surfaceVariant = CreamSurfaceAlt,
    onSurfaceVariant = InkSecondary,
    outline = Divider,
    outlineVariant = Divider,
    error = DestructiveRed,
    onError = Color.White
)

val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlueDark,
    onPrimaryContainer = Color.White,
    secondary = DarkInkSecondary,
    onSecondary = DarkBg,
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkInkSecondary,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
    error = DestructiveRed,
    onError = Color.White
)
