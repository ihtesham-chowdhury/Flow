package com.flowbangla.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Material 3 tints elevated surfaces with the primary colour (that is where
// the violet cast comes from). Kill that tint so surfaces stay neutral.
private val DarkScheme = darkColorScheme(
    background        = DarkCanvas,
    onBackground      = DarkInk,
    surface           = DarkSurface,
    onSurface         = DarkInk,
    surfaceVariant    = DarkSurfaceRaised,
    onSurfaceVariant  = DarkInkMuted,
    primary           = DarkInk,          // primary actions are ivory, not a hue
    onPrimary         = DarkInkOnAccent,
    secondary         = OchreDark,
    onSecondary       = DarkInkOnAccent,
    error             = VermilionDark,
    onError           = DarkInkOnAccent,
    outline           = DarkHairline,
    outlineVariant    = DarkHairline,
    surfaceTint       = Color.Transparent // no tonal tint, no violet cast
)

private val LightScheme = lightColorScheme(
    background        = LightCanvas,
    onBackground      = LightInk,
    surface           = LightSurface,
    onSurface         = LightInk,
    surfaceVariant    = LightSurfaceRaised,
    onSurfaceVariant  = LightInkMuted,
    primary           = LightInk,
    onPrimary         = LightInkOnAccent,
    secondary         = OchreLight,
    onSecondary       = LightInkOnAccent,
    error             = VermilionLight,
    onError           = LightInkOnAccent,
    outline           = LightHairline,
    outlineVariant    = LightHairline,
    surfaceTint       = Color.Transparent
)

val LocalAccentColor = staticCompositionLocalOf { AccentIvory }

@Composable
fun FlowBanglaTheme(
    darkTheme: Boolean = true, // Dark-first per design spec
    isBangla: Boolean = false,
    accentColor: Color = AccentIvory,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkScheme else LightScheme
    val colorScheme = baseScheme.copy(
        primary = accentColor
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAccentColor provides accentColor) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = flowTypography(isBangla),
            shapes = FlowShapes,
            content = content
        )
    }
}
