package com.catmusic.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CatColorScheme = darkColorScheme(
    primary = CatViolet,
    onPrimary = Color(0xFF231834),
    primaryContainer = Color(0xFF2A203C),
    onPrimaryContainer = CatCream,
    secondary = CatPeach,
    onSecondary = Color(0xFF2B1713),
    secondaryContainer = Color(0xFF352328),
    onSecondaryContainer = CatCream,
    tertiary = CatMint,
    onTertiary = Color(0xFF0D2B27),
    background = CatBg,
    onBackground = CatTextPrimary,
    surface = CatBgSoft,
    onSurface = CatTextPrimary,
    surfaceVariant = CatSurface,
    onSurfaceVariant = CatTextSecondary,
    outline = Color(0xFF5B5369),
    outlineVariant = Color(0xFF2C2638),
    error = CatDanger,
    onError = Color(0xFF2A0D12)
)

val CatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun CatMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CatColorScheme,
        typography = CatTypography,
        shapes = CatShapes,
        content = content
    )
}
