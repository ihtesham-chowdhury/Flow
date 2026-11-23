package com.flowbangla.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- Static Raw Palette Tokens ----
val DarkCanvasStatic        = Color(0xFF0E1013)
val DarkSurfaceStatic       = Color(0xFF171A1F)
val DarkSurfaceRaisedStatic = Color(0xFF21252C)
val DarkSurfaceSunkenStatic = Color(0xFF101216)
val DarkHairlineStatic      = Color(0xFF272B33)
val DarkInkStatic           = Color(0xFFF7F8FA)
val DarkInkMutedStatic      = Color(0xFF959CA8)

val LightCanvasStatic        = Color(0xFFF3F4F6)
val LightSurfaceStatic       = Color(0xFFFFFFFF)
val LightSurfaceRaisedStatic = Color(0xFFF8F9FA)
val LightSurfaceSunkenStatic = Color(0xFFE7E9EE)
val LightHairlineStatic      = Color(0xFFDDE1E8)
val LightInkStatic           = Color(0xFF111317)
val LightInkMutedStatic      = Color(0xFF5B6270)

val SignalRedDark  = Color(0xFFEA3829)
val SignalRedLight = Color(0xFFD92D20)

// Dynamic Theme-Aware Getters for Composables:
// Automatically transforms every composable across all screens when switching Light / Dark!
val DarkCanvas: Color @Composable get() = LocalFlowColors.current.canvas
val DarkSurface: Color @Composable get() = LocalFlowColors.current.surface
val DarkSurfaceRaised: Color @Composable get() = LocalFlowColors.current.surfaceRaised
val DarkSurfaceSunken: Color @Composable get() = LocalFlowColors.current.surfaceSunken
val DarkHairline: Color @Composable get() = LocalFlowColors.current.hairline
val DarkInk: Color @Composable get() = LocalFlowColors.current.inkPrimary
val DarkInkMuted: Color @Composable get() = LocalFlowColors.current.inkSecondary
val DarkInkOnAccent: Color @Composable get() = LocalFlowColors.current.onSignalRed

val LightCanvas: Color @Composable get() = LocalFlowColors.current.canvas
val LightSurface: Color @Composable get() = LocalFlowColors.current.surface
val LightSurfaceRaised: Color @Composable get() = LocalFlowColors.current.surfaceRaised
val LightSurfaceSunken: Color @Composable get() = LocalFlowColors.current.surfaceSunken
val LightHairline: Color @Composable get() = LocalFlowColors.current.hairline
val LightInk: Color @Composable get() = LocalFlowColors.current.inkPrimary
val LightInkMuted: Color @Composable get() = LocalFlowColors.current.inkSecondary
val LightInkOnAccent: Color @Composable get() = LocalFlowColors.current.onSignalRed

// Shared status colors
val VermilionDark  = Color(0xFFEA3829)
val VermilionLight = Color(0xFFD92D20)
val OchreDark      = Color(0xFFEAB308)
val OchreLight     = Color(0xFFA16207)
val SageDark       = Color(0xFF22C55E)
val SageLight      = Color(0xFF15803D)

// Selectable Accent Options (Signal Red is signature #1)
val AccentSignalRed = Color(0xFFEA3829)
val AccentIvory     = Color(0xFFF7F8FA)
val AccentOchre     = Color(0xFFEAB308)
val AccentSage      = Color(0xFF22C55E)
val AccentSky       = Color(0xFF38BDF8)
val AccentRose      = Color(0xFFEA3829) // Mapped to Signal Red for crisp industrial aesthetic
val AccentViolet    = Color(0xFFA78BFA)

data class FlowAccentOption(
    val key: String,
    val nameEn: String,
    val nameBn: String,
    val color: Color
)

val AvailableAccentOptions = listOf(
    FlowAccentOption("red", "Signal Red", "সিগন্যাল রেড", AccentSignalRed),
    FlowAccentOption("ivory", "Classic Ivory", "আইভরি ক্লাসিক", AccentIvory),
    FlowAccentOption("ochre", "Ochre Gold", "গোল্ড ওকর", AccentOchre),
    FlowAccentOption("sage", "Sage Emerald", "সেজ এমারেল্ড", AccentSage),
    FlowAccentOption("sky", "Nordic Sky", "নর্ডিক স্কাই", AccentSky),
    FlowAccentOption("violet", "Amethyst", "অ্যামেথিস্ট ভায়োলেট", AccentViolet)
)

fun getAccentColor(key: String): Color {
    return when (key.lowercase()) {
        "red", "signal", "rose" -> AccentSignalRed
        else -> AvailableAccentOptions.firstOrNull { it.key == key }?.color ?: AccentSignalRed
    }
}

// Backward compatibility aliases mapping legacy names cleanly to the new palette
val PrimaryBlue: Color @Composable get() = LocalFlowColors.current.inkPrimary
val PrimaryBlueVariant: Color @Composable get() = LocalFlowColors.current.surfaceRaised
val AccentCyan: Color @Composable get() = LocalFlowColors.current.signalRed
val SuccessGreen: Color @Composable get() = LocalFlowColors.current.statusReady
val RecordingRed: Color @Composable get() = LocalFlowColors.current.signalRed
val ProcessingAmber: Color @Composable get() = LocalFlowColors.current.statusPaid
val BackgroundDark: Color @Composable get() = LocalFlowColors.current.canvas
val SurfaceDark: Color @Composable get() = LocalFlowColors.current.surface
val SurfaceCard: Color @Composable get() = LocalFlowColors.current.surfaceRaised
val TextPrimary: Color @Composable get() = LocalFlowColors.current.inkPrimary
val TextSecondary: Color @Composable get() = LocalFlowColors.current.inkSecondary
