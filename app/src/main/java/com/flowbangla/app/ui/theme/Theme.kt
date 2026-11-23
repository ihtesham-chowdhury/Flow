package com.flowbangla.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Immutable
data class FlowColors(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val hairline: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val signalRed: Color,
    val onSignalRed: Color,
    val statusReady: Color,
    val statusPaid: Color,
    val statusFree: Color
)

val DarkFlowColors = FlowColors(
    isDark = true,
    canvas = Color(0xFF0E1013),
    surface = Color(0xFF171A1F),
    surfaceRaised = Color(0xFF21252C),
    surfaceSunken = Color(0xFF101216),
    hairline = Color(0xFF272B33),
    inkPrimary = Color(0xFFF7F8FA),
    inkSecondary = Color(0xFF959CA8),
    signalRed = Color(0xFFEA3829),
    onSignalRed = Color(0xFFFFFFFF),
    statusReady = Color(0xFF22C55E),
    statusPaid = Color(0xFFEAB308),
    statusFree = Color(0xFF38BDF8)
)

val LightFlowColors = FlowColors(
    isDark = false,
    canvas = Color(0xFFF3F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF8F9FA),
    surfaceSunken = Color(0xFFE7E9EE),
    hairline = Color(0xFFDDE1E8),
    inkPrimary = Color(0xFF111317),
    inkSecondary = Color(0xFF5B6270),
    signalRed = Color(0xFFD92D20),
    onSignalRed = Color(0xFFFFFFFF),
    statusReady = Color(0xFF15803D),
    statusPaid = Color(0xFFA16207),
    statusFree = Color(0xFF0284C7)
)

val LocalFlowColors = staticCompositionLocalOf { DarkFlowColors }

object FlowTheme {
    val colors: FlowColors
        @Composable
        get() = LocalFlowColors.current
}

val LocalAccentColor = staticCompositionLocalOf { AccentSignalRed }

@Composable
fun FlowBanglaTheme(
    appearanceMode: String = "dark",
    isBangla: Boolean = false,
    accentColor: Color = AccentSignalRed,
    content: @Composable () -> Unit
) {
    val useDark = when (appearanceMode.lowercase()) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val flowColors = if (useDark) DarkFlowColors else LightFlowColors

    val materialScheme = if (useDark) {
        darkColorScheme(
            primary = accentColor,
            onPrimary = flowColors.onSignalRed,
            background = flowColors.canvas,
            onBackground = flowColors.inkPrimary,
            surface = flowColors.surface,
            onSurface = flowColors.inkPrimary,
            surfaceVariant = flowColors.surfaceRaised,
            onSurfaceVariant = flowColors.inkSecondary,
            outline = flowColors.hairline,
            surfaceTint = Color.Transparent
        )
    } else {
        lightColorScheme(
            primary = accentColor,
            onPrimary = flowColors.onSignalRed,
            background = flowColors.canvas,
            onBackground = flowColors.inkPrimary,
            surface = flowColors.surface,
            onSurface = flowColors.inkPrimary,
            surfaceVariant = flowColors.surfaceRaised,
            onSurfaceVariant = flowColors.inkSecondary,
            outline = flowColors.hairline,
            surfaceTint = Color.Transparent
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = flowColors.canvas.toArgb()
                window.navigationBarColor = flowColors.canvas.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !useDark
                controller.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    CompositionLocalProvider(
        LocalFlowColors provides flowColors,
        LocalAccentColor provides accentColor
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = flowTypography(isBangla),
            shapes = FlowShapes,
            content = content
        )
    }
}

@Composable
fun FlowBanglaTheme(
    darkTheme: Boolean,
    isBangla: Boolean = false,
    accentColor: Color = AccentSignalRed,
    content: @Composable () -> Unit
) = FlowBanglaTheme(
    appearanceMode = if (darkTheme) "dark" else "light",
    isBangla = isBangla,
    accentColor = accentColor,
    content = content
)
