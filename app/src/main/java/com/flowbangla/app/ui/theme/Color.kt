package com.flowbangla.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Dark theme (default) ------------------------------------------------
// Warm neutral obsidian canvas. Deliberately not blue-slate.
val DarkCanvas        = Color(0xFF0D0E10)
val DarkSurface       = Color(0xFF16181B)
val DarkSurfaceRaised = Color(0xFF1F2226)
val DarkSurfaceSunken = Color(0xFF111316)
val DarkHairline      = Color(0xFF2A2E33)
val DarkInk           = Color(0xFFEEEBE3)   // ivory, primary text and primary buttons
val DarkInkMuted      = Color(0xFF9EA3AB)
val DarkInkOnAccent   = Color(0xFF0D0E10)

// ---- Light theme ---------------------------------------------------------
val LightCanvas        = Color(0xFFECEDE9)
val LightSurface       = Color(0xFFF7F7F4)
val LightSurfaceRaised = Color(0xFFFFFFFF)
val LightSurfaceSunken = Color(0xFFE2E4DF)
val LightHairline      = Color(0xFFD3D6D0)
val LightInk           = Color(0xFF1B1E22)
val LightInkMuted      = Color(0xFF5B6068)
val LightInkOnAccent   = Color(0xFFFFFFFF)

// ---- Shared state colours (same meaning in both themes) -----------------
// Vermilion means LIVE recording only. Do not reuse it for destructive actions.
val VermilionDark  = Color(0xFFE8473A)
val VermilionLight = Color(0xFFC23A2D)
// Ochre means working or needs attention. Graphic only in light theme.
val OchreDark      = Color(0xFFE3B257)
val OchreLight     = Color(0xFF9A6B12)
// Sage means success.
val SageDark       = Color(0xFF8FBF9F)
val SageLight      = Color(0xFF36704D)

// Backward compatibility aliases mapping legacy names cleanly to the new palette
val PrimaryBlue = DarkInk
val PrimaryBlueVariant = DarkSurfaceRaised
val AccentCyan = DarkInk
val SuccessGreen = SageDark
val RecordingRed = VermilionDark
val ProcessingAmber = OchreDark
val BackgroundDark = DarkCanvas
val SurfaceDark = DarkSurface
val SurfaceCard = DarkSurfaceRaised
val TextPrimary = DarkInk
val TextSecondary = DarkInkMuted
