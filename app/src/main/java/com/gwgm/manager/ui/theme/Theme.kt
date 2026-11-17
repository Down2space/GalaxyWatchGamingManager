package com.gwgm.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Neon = Color(0xFF39FF14)
val Magenta = Color(0xFFFF2BD6)
val Amber = Color(0xFFFFB020)
val Danger = Color(0xFFFF4D4D)
val Cyan = Color(0xFF2BD9FF)

private val Scheme = darkColorScheme(
    primary = Neon,
    onPrimary = Color(0xFF041004),
    secondary = Magenta,
    tertiary = Cyan,
    background = Color(0xFF0B0B14),
    surface = Color(0xFF14142A),
    surfaceVariant = Color(0xFF1E1E3A),
    onSurface = Color(0xFFE8E8F5),
    onSurfaceVariant = Color(0xFFB0B0CC),
    error = Danger,
)

@Composable
fun GwgmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
