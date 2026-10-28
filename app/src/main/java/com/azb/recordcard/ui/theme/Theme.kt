package com.azb.recordcard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = CardLight,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Amber600,
    onSecondary = CardLight,
    secondaryContainer = Amber100,
    onSecondaryContainer = Color(0xFF3D2600),
    tertiary = Teal500,
    onTertiary = CardLight,
    background = SurfaceLight,
    onBackground = Ink900,
    surface = CardLight,
    onSurface = Ink900,
    surfaceVariant = VariantLight,
    onSurfaceVariant = Ink500,
    surfaceTint = Teal700,
    surfaceContainerLowest = CardLight,
    surfaceContainerLow = Color(0xFFF1F6F3),
    surfaceContainer = Color(0xFFEBF1EE),
    surfaceContainerHigh = Color(0xFFE5ECE9),
    surfaceContainerHighest = Color(0xFFDFE7E4),
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = DangerRed,
    onError = CardLight,
    errorContainer = Color(0xFFFBE4E1),
    onErrorContainer = Color(0xFF5B1712),
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Teal900,
    primaryContainer = Teal800,
    onPrimaryContainer = Teal100,
    secondary = Amber200,
    onSecondary = Color(0xFF3D2600),
    secondaryContainer = Color(0xFF5A3B00),
    onSecondaryContainer = Amber100,
    tertiary = Teal500,
    onTertiary = Teal900,
    background = SurfaceDark,
    onBackground = Color(0xFFE6EFEB),
    surface = CardDark,
    onSurface = Color(0xFFE6EFEB),
    surfaceVariant = VariantDark,
    onSurfaceVariant = Ink300,
    surfaceTint = Teal200,
    surfaceContainerLowest = Color(0xFF0A100E),
    surfaceContainerLow = Color(0xFF111A17),
    surfaceContainer = Color(0xFF16211D),
    surfaceContainerHigh = Color(0xFF1B2723),
    surfaceContainerHighest = Color(0xFF21302B),
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun RecordCardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
