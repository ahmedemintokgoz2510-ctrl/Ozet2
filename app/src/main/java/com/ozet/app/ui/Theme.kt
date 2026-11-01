package com.ozet.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pal {
    val Red = Color(0xFFE02B2B)
    val RedLight = Color(0xFFEC3B3B)
    val RedDark = Color(0xFFCF2020)
    val RedSoft = Color(0xFFFDECEC)
    val Bg = Color(0xFFFAFAFA)
    val Line = Color(0xFFEAEAEA)
    val Ink = Color(0xFF14161A)
    val Sub = Color(0xFF6E7480)
}

@Composable
fun OzetTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Pal.Red,
        onPrimary = Color.White,
        primaryContainer = Pal.RedSoft,
        onPrimaryContainer = Pal.RedDark,
        background = Pal.Bg,
        onBackground = Pal.Ink,
        surface = Color.White,
        onSurface = Pal.Ink,
        surfaceVariant = Color(0xFFF2F2F2),
        onSurfaceVariant = Pal.Sub,
        secondaryContainer = Pal.RedSoft,
        onSecondaryContainer = Pal.RedDark,
        outline = Pal.Line,
        outlineVariant = Pal.Line
    )
    MaterialTheme(colorScheme = colors, content = content)
}
