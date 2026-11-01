package com.roll0the1dice.voxread.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF00A5F5)
val OnPrimaryWhite = Color(0xFFFFFFFF)
val BackgroundLight = Color(0xFFF8F9FA)
val SurfaceLight = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF252A32)
val TextSecondary = Color(0xFF60656E)
val TextTertiary = Color(0xFF90A4AE)
val BorderLight = Color(0xFFE2E8F0)
val PlayerBackground = Color(0xFFF4F5F7)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryWhite,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0077B6),
    secondary = Color(0xFF008CD0),
    background = BackgroundLight,
    surface = SurfaceLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = TextSecondary,
    outline = BorderLight
)

@Composable
fun VoxReadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
