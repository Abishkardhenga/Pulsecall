package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = TealPulse,
    onPrimary = OnTealPulse,
    primaryContainer = TealPulseContainer,
    onPrimaryContainer = TealPulseBright,
    secondary = SkySignal,
    onSecondary = Color(0xFF062033),
    secondaryContainer = SkySignalContainer,
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = AmberWait,
    onTertiary = Color(0xFF2E1A00),
    error = CoralEnd,
    onError = Color.White,
    errorContainer = CoralEndContainer,
    onErrorContainer = Color(0xFFFECACA),
    background = MidnightBg,
    onBackground = InkLight,
    surface = MidnightSurface,
    onSurface = InkLight,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = InkMuted,
    outline = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = TealLightContainer,
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    error = CoralEnd,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = LightMistBg,
    onBackground = InkDark,
    surface = LightSurface,
    onSurface = InkDark,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = InkDarkMuted,
    outline = OutlineLight
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PulseCallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
