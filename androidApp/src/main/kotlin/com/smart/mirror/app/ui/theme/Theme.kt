package com.smart.mirror.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Scrim behind controls so text/icons keep >= 4.5:1 contrast over any preview (NFR-10). */
val ControlScrim = Color.Black.copy(alpha = 0.6f)
val OnControlScrim = Color.White

private val MirrorColors = darkColorScheme(
    primary = Color(0xFFFFD58A),
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5B4300),
    onPrimaryContainer = Color(0xFFFFDEA4),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF141414),
    onSurface = Color.White,
)

@Composable
fun SmartMirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MirrorColors, content = content)
}
