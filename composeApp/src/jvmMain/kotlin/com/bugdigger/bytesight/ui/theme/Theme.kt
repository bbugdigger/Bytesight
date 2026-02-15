package com.bugdigger.bytesight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Bytesight dark color scheme - a professional dark theme for code analysis.
 */
private val BytesightDarkColorScheme = darkColorScheme(
    // Primary colors - cyan/teal accent
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF97F0FF),

    // Secondary colors - amber accent for highlights
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF462A00),
    secondaryContainer = Color(0xFF643F00),
    onSecondaryContainer = Color(0xFFFFDDB3),

    // Tertiary colors - purple for special elements
    tertiary = Color(0xFFCE93D8),
    onTertiary = Color(0xFF3E0047),
    tertiaryContainer = Color(0xFF5B0069),
    onTertiaryContainer = Color(0xFFF6D9FF),

    // Error colors
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Background colors - dark gray
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFE6E1E5),

    // Surface colors - slightly lighter than background
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFCAC4D0),

    // Outline colors
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF444746),

    // Inverse colors
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF006970),

    // Other
    surfaceTint = Color(0xFF4DD0E1),
    scrim = Color(0xFF000000),
)

/**
 * Bytesight application theme.
 * Uses a dark color scheme optimized for code viewing and analysis.
 */
@Composable
fun BytesightTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BytesightDarkColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
