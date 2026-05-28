package com.localchatbot.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Display = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 40.sp
)

private val TitleLarge = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp
)

private val Body = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 22.sp
)

private val BodySmall = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

private val Label = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp
)

val Mono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

val AppTypography = Typography(
    displayLarge = Display,
    headlineLarge = Display.copy(fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TitleLarge,
    titleMedium = TitleLarge.copy(fontSize = 16.sp),
    bodyLarge = Body,
    bodyMedium = BodySmall,
    bodySmall = BodySmall.copy(fontSize = 13.sp),
    labelLarge = Label.copy(fontSize = 14.sp),
    labelMedium = Label,
    labelSmall = Label.copy(fontSize = 11.sp)
)
