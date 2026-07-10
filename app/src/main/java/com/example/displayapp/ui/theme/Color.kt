package com.example.displayapp.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand blues — vivid electric blue used for the "App" wordmark, the active
// bottom-tab, and Material primary in both Light and Dark.
// ---------------------------------------------------------------------------
val EvBlue            = Color(0xFF3B82F6)   // primary in dark mode
val EvBlueBright      = Color(0xFF60A5FA)
val EvBlueDeep        = Color(0xFF2563EB)   // primary in light mode
val EvBlueNavy        = Color(0xFF0F2A66)
val EvBlueInk         = Color(0xFF051431)
val EvBlueIce         = Color(0xFFDDEAFF)
val EvBlueGlow        = Color(0x663B82F6)
val EvBlueCyan        = Color(0xFF22D3EE)
val EvBlueCyanDeep    = Color(0xFF0891B2)

// ---------------------------------------------------------------------------
// Gauge track — the unfilled trailing arc. The progress-arc gradient is derived
// from the theme's color roles (see SpeedometerGauge), not fixed literals.
// ---------------------------------------------------------------------------
val GaugeTrack        = Color(0xFF3F3F46)   // unfilled trailing arc (dark gray)
val GaugeTrackLight   = Color(0xFFE4E7EB)   // unfilled arc in light mode

// ---------------------------------------------------------------------------
// Semantic accents — kept stable across themes for vehicle-state readability.
// ---------------------------------------------------------------------------
val EvGreen      = Color(0xFF22C55E)   // "Connect" pill / connected status
val EvGreenDeep  = Color(0xFF16A34A)
val EvCyan       = Color(0xFF00E5FF)   // legacy cyan (kept for older callers)
val EvCyanSoft   = Color(0xFF4DD0E1)
val EvLime       = Color(0xFF76FF03)
val EvAmber      = Color(0xFFFFC400)
val EvRed        = Color(0xFFFF1744)
val EvViolet     = Color(0xFF7C4DFF)
val EvIce        = Color(0xFFE0F7FA)

// Mode pill colors (semantic — unchanged across themes). REGEN uses a bright
// cyan so it's visually distinct from NORMAL's blue while still feeling
// "electric." ECO leans toward green-teal rather than pure lime so it reads
// as efficient, not as a warning.
val ModePark    = Color(0xFF78909C)
val ModeEco     = Color(0xFF1FE0B5)   // green-teal
val ModeNormal  = EvBlue
val ModeSport   = Color(0xFFFF5722)   // red-orange
val ModeRegen   = Color(0xFF22D3EE)   // cyan

// ---------------------------------------------------------------------------
// Dark surfaces — deep navy-black cockpit matching the mockup.
// Hue picked so cards have a slight blue cast against a near-black bg.
// ---------------------------------------------------------------------------
val CarbonBg          = Color(0xFF080D17)   // app background
val CarbonSurface     = Color(0xFF0E1626)   // first elevation
val CarbonSurfaceHi   = Color(0xFF131B2C)   // cards
val CarbonSurfaceTop  = Color(0xFF1A2336)   // raised / selected card
val CarbonOutline     = Color(0xFF1F2A40)
val CarbonOnSurface   = Color(0xFFE6EDF8)
val CarbonOnVariant   = Color(0xFF8B95A8)

// Glass overlays (used by GlassCard)
val GlassTint         = Color(0x1AFFFFFF)
val GlassStroke       = Color(0x33FFFFFF)
val GlassShadow       = Color(0xCC000000)

// ---------------------------------------------------------------------------
// Light surfaces — clean futuristic with soft blue tint, mirrors the dark mode.
// ---------------------------------------------------------------------------
val PaperBg          = Color(0xFFF3F6FB)
val PaperSurface     = Color(0xFFFFFFFF)
val PaperSurfaceHi   = Color(0xFFE9EFF8)
val PaperOutline     = Color(0xFFC7D2E5)
val PaperOnSurface   = Color(0xFF0B1224)
val PaperOnVariant   = Color(0xFF4A5872)

val PaperGlassTint    = Color(0x14001A41)
val PaperGlassStroke  = Color(0x332563EB)
