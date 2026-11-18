package com.iptv.tv

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFFFEA00),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF090A0F),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF131722),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E2330),
    onSurfaceVariant = Color(0xFFE2E8F0)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val customBg = Store.str("custom_bg", "")
    val bg = if (customBg.isNotBlank()) {
        try { Color(android.graphics.Color.parseColor(customBg)) } catch(e: Exception) { DarkColorScheme.background }
    } else {
        DarkColorScheme.background
    }

    val colors = DarkColorScheme.copy(
        background = bg,
        surface = bg.copy(alpha = 0.85f)
    )

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

