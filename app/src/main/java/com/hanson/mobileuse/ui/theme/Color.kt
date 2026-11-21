package com.hanson.mobileuse.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class HansonUiStyle(val label: String, val badge: String) {
    AHABOARD_PAPER("Ahaboard Paper (Pure Achromatic Light)", "PAPER"),
    AHABOARD_CARBON("Ahaboard Carbon (Pure Achromatic Dark)", "CARBON");

    fun next(): HansonUiStyle = when (this) {
        AHABOARD_PAPER -> AHABOARD_CARBON
        AHABOARD_CARBON -> AHABOARD_PAPER
    }
}

/**
 * Pure Achromatic (무채색, 0% Hue Saturation) Design Tokens reverse-engineered from ahaboard.ai.
 * Eliminates all chromatic noise (no cyan, violet, neon green, or orange).
 * Relies strictly on luminance contrast, 1dp hairlines, and 3dp solid tactile ink lips.
 */
object ObsidianColors {
    var currentStyle by mutableStateOf(HansonUiStyle.AHABOARD_PAPER)

    var isDark: Boolean
        get() = currentStyle == HansonUiStyle.AHABOARD_CARBON
        set(value) {
            currentStyle = if (value) HansonUiStyle.AHABOARD_CARBON else HansonUiStyle.AHABOARD_PAPER
        }

    fun toggleTheme() {
        currentStyle = currentStyle.next()
    }

    // --paper: Main dot-grid canvas background
    val Background: Color
        get() = if (isDark) Color(0xFF09090B) else Color(0xFFFAFAFA)

    // --surface: Primary card surface (.brand-card)
    val Surface: Color
        get() = if (isDark) Color(0xFF121214) else Color(0xFFFFFFFF)

    // --surface-subtle: Secondary / chip surface
    val SurfaceHigh: Color
        get() = if (isDark) Color(0xFF18181B) else Color(0xFFF4F4F5)

    // --surface-active: Pressed / elevated surface
    val SurfaceTop: Color
        get() = if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)

    val GlassWhite: Color
        get() = if (isDark) Color(0x12FFFFFF) else Color(0x0A09090B)

    // --line: 1dp hairline border
    val GlassBorder: Color
        get() = if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)

    // --line-strong: Emphasized hairline
    val GlassHighlight: Color
        get() = if (isDark) Color(0xFF3F3F46) else Color(0xFFD4D4D8)

    // --ink: Primary high-contrast monochrome ink (replaces all colorful accents)
    val Accent: Color
        get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)

    val AccentDim: Color
        get() = if (isDark) Color(0x1FFAFAFA) else Color(0x1209090B)

    // Secondary monochrome tone (strictly 0% saturation)
    val AccentPurple: Color
        get() = if (isDark) Color(0xFFD4D4D8) else Color(0xFF27272A)

    val AccentPurpleDim: Color
        get() = if (isDark) Color(0x1FD4D4D8) else Color(0x1227272A)

    val TextPrimary: Color
        get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)

    val TextSecondary: Color
        get() = if (isDark) Color(0xFFA1A1AA) else Color(0xFF52525B)

    val TextMuted: Color
        get() = if (isDark) Color(0xFF71717A) else Color(0xFF71717A)

    val OnAccent: Color
        get() = if (isDark) Color(0xFF09090B) else Color(0xFFFAFAFA)

    // Status tokens are also strictly achromatic grayscale
    val Success: Color
        get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)

    val SuccessDim: Color
        get() = if (isDark) Color(0x1FFAFAFA) else Color(0x1209090B)

    val Warning: Color
        get() = if (isDark) Color(0xFFA1A1AA) else Color(0xFF52525B)

    val WarningDim: Color
        get() = if (isDark) Color(0x1FA1A1AA) else Color(0x1452525B)

    val Error: Color
        get() = if (isDark) Color(0xFFD4D4D8) else Color(0xFF18181B)

    val ErrorDim: Color
        get() = if (isDark) Color(0x24D4D4D8) else Color(0x1618181B)

    val Divider: Color
        get() = if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)

    val DotGridColor: Color
        get() = if (isDark) Color(0x14FFFFFF) else Color(0x1409090B)
}
