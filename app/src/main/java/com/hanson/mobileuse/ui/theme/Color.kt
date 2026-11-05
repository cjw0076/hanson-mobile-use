package com.hanson.mobileuse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class HansonUiStyle(val label: String, val badge: String) {
    TACTICAL_MONO("Tactical Mono (Nothing × TE)", "MONO"),
    SPATIAL_GLASS("Spatial Titanium (Linear × Vision)", "GLASS"),
    EDITORIAL_IVORY("Editorial Ivory (Claude × Ink)", "IVORY");

    fun next(): HansonUiStyle = when (this) {
        TACTICAL_MONO -> SPATIAL_GLASS
        SPATIAL_GLASS -> EDITORIAL_IVORY
        EDITORIAL_IVORY -> TACTICAL_MONO
    }
}

/**
 * Dynamic Multi-Concept Design System supporting all 3 generated UI Aesthetics:
 *  - Concept A: TACTICAL_MONO (Jet carbon, monospace precision, Teenage Engineering signal orange)
 *  - Concept B: SPATIAL_GLASS (Deep titanium slate, 1px specular borders, cyan/violet protocol nodes)
 *  - Concept C: EDITORIAL_IVORY (Warm alabaster paper, deep charcoal ink, sage verification badges)
 */
object ObsidianColors {
    var currentStyle by mutableStateOf(HansonUiStyle.SPATIAL_GLASS)

    val Background: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFF09090B)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF070A10)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFFF6F4EE)
        }

    val Surface: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFF121215)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF0E131F)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFFECE9E0)
        }

    val SurfaceHigh: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFF1A1A1F)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF161E2E)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFFE2DDD2)
        }

    val SurfaceTop: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFF232329)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF1E293B)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFFD6D0C4)
        }

    val GlassWhite: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x10FFFFFF)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x1638BDF8)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x0F141413)
        }

    val GlassBorder: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x26FFFFFF)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x3838BDF8)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x24141413)
        }

    val GlassHighlight: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x33FFFFFF)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x4538BDF8)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x30141413)
        }

    val Accent: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFFFF5722) // Teenage Engineering Signal Orange
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF38BDF8) // Spatial Cyan
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFF141413) // Deep Charcoal Ink
        }

    val AccentDim: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x28FF5722)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x2838BDF8)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x18141413)
        }

    val AccentPurple: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFFD4D4D8) // Tactical Mono Silver
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFFA855F7) // Protocol Node Violet
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFF3B5944) // Editorial Sage
        }

    val AccentPurpleDim: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x22D4D4D8)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x24A855F7)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x203B5944)
        }

    val TextPrimary: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFFFAFAFA)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFFF1F5F9)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFF141413)
        }

    val TextSecondary: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFFA1A1AA)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF94A3B8)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFF57534E)
        }

    val TextMuted: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFF71717A)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF64748B)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFF78716C)
        }

    val OnAccent: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFFFFFFFF)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF06101E)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFFF6F4EE)
        }

    val Success: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0xFF10B981)
            HansonUiStyle.SPATIAL_GLASS -> Color(0xFF34D399)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0xFF2E6F40)
        }

    val SuccessDim: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x2410B981)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x2434D399)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x202E6F40)
        }

    val Warning: Color = Color(0xFFFBBF24)
    val WarningDim: Color = Color(0x24FBBF24)
    val Error: Color = Color(0xFFEF4444)
    val ErrorDim: Color = Color(0x24EF4444)
    val Divider: Color
        get() = when (currentStyle) {
            HansonUiStyle.TACTICAL_MONO -> Color(0x1FFFFFFF)
            HansonUiStyle.SPATIAL_GLASS -> Color(0x2438BDF8)
            HansonUiStyle.EDITORIAL_IVORY -> Color(0x1C141413)
        }
}
