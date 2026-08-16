package com.noxos.audit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.noxos.audit.ThemeMode

private val DarkWardenScheme = darkColorScheme(
    primary = WardenColors.DarkAccent,
    onPrimary = WardenColors.DarkOnAccent,
    secondary = WardenColors.DarkAccent,
    onSecondary = WardenColors.DarkOnAccent,
    tertiary = WardenColors.DarkFlagged,
    onTertiary = WardenColors.DarkBackground,
    error = WardenColors.DarkError,
    onError = WardenColors.DarkTextPrimary,
    background = WardenColors.DarkBackground,
    onBackground = WardenColors.DarkTextPrimary,
    surface = WardenColors.DarkSurface,
    onSurface = WardenColors.DarkTextPrimary,
    surfaceVariant = WardenColors.DarkSurface,
    onSurfaceVariant = WardenColors.DarkTextSecondary,
    outline = WardenColors.DarkSurfaceBorder,
    outlineVariant = WardenColors.DarkDivider
)

private val LightWardenScheme = lightColorScheme(
    primary = WardenColors.LightAccent,
    onPrimary = WardenColors.LightOnAccent,
    secondary = WardenColors.LightAccent,
    onSecondary = WardenColors.LightOnAccent,
    tertiary = WardenColors.LightFlagged,
    onTertiary = WardenColors.LightSurface,
    error = WardenColors.LightError,
    onError = WardenColors.LightSurface,
    background = WardenColors.LightBackground,
    onBackground = WardenColors.LightTextPrimary,
    surface = WardenColors.LightSurface,
    onSurface = WardenColors.LightTextPrimary,
    surfaceVariant = WardenColors.LightSurface,
    onSurfaceVariant = WardenColors.LightTextSecondary,
    outline = WardenColors.LightSurfaceBorder,
    outlineVariant = WardenColors.LightSurfaceBorder
)

val LocalWardenTertiaryText = compositionLocalOf { WardenColors.DarkTextTertiary }

@Composable
fun WardenTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (dark) DarkWardenScheme else LightWardenScheme
    val tertiaryText = if (dark) WardenColors.DarkTextTertiary else WardenColors.LightTextTertiary

    CompositionLocalProvider(LocalWardenTertiaryText provides tertiaryText) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WardenTypography,
            content = content
        )
    }
}
