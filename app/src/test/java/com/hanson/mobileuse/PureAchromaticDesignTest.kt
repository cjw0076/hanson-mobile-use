package com.hanson.mobileuse

import androidx.compose.ui.graphics.Color
import com.hanson.mobileuse.ui.components.GenerativeUiRendererDefaults
import com.hanson.mobileuse.ui.theme.HansonUiStyle
import com.hanson.mobileuse.ui.theme.ObsidianColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Comprehensive E2E / Integration Test Suite for Milestone 2 & Features 16, 17:
 * - Feature 16: Pure Achromatic Design Tokens (`ObsidianColors` across Paper Light & Carbon Dark)
 * - Feature 17: Defensive Grayscale Filter on Dynamic Web Containers
 *
 * Verifies:
 * 1. 0% Saturation / Monochromatic Palette (R == G == B or neutral zinc where channel divergence is <= 0.04).
 * 2. Complete absence of chromatic noise (no vibrant red, green, blue, yellow, orange, violet).
 * 3. Status tokens (Success, Warning, Error) are strictly achromatic grayscale.
 * 4. Theme switching contract between Ahaboard Paper and Ahaboard Carbon.
 * 5. High-contrast luminance inversion between background and foreground ink.
 * 6. Web container defensive CSS filter injection (`filter: grayscale(100%) !important`).
 */
class PureAchromaticDesignTest {

    private val originalStyle = ObsidianColors.currentStyle

    @Before
    fun setUp() {
        ObsidianColors.currentStyle = HansonUiStyle.AHABOARD_PAPER
    }

    @After
    fun tearDown() {
        ObsidianColors.currentStyle = originalStyle
    }

    /**
     * Calculates the HSV saturation of a Compose Color.
     * Saturation = (max(R,G,B) - min(R,G,B)) / max(R,G,B) (or 0 if max == 0).
     */
    private fun getHsvSaturation(c: Color): Float {
        val r = c.red
        val g = c.green
        val b = c.blue
        val maxVal = max(r, max(g, b))
        val minVal = min(r, min(g, b))
        if (maxVal <= 0.001f) return 0f
        return (maxVal - minVal) / maxVal
    }

    /**
     * Checks if a color is purely monochromatic / neutral zinc.
     * In the Ahaboard/Obsidian palette, R is identical to G, and B is within 0.04.
     */
    private fun isAchromatic(c: Color, maxDivergence: Float = 0.05f): Boolean {
        val rgDiff = abs(c.red - c.green)
        val rbDiff = abs(c.red - c.blue)
        val gbDiff = abs(c.green - c.blue)
        return rgDiff <= 0.005f && rbDiff <= maxDivergence && gbDiff <= maxDivergence
    }

    private fun getAllSemanticColors(): List<Pair<String, Color>> = listOf(
        "Background" to ObsidianColors.Background,
        "Surface" to ObsidianColors.Surface,
        "SurfaceHigh" to ObsidianColors.SurfaceHigh,
        "SurfaceTop" to ObsidianColors.SurfaceTop,
        "GlassBorder" to ObsidianColors.GlassBorder,
        "GlassHighlight" to ObsidianColors.GlassHighlight,
        "Accent" to ObsidianColors.Accent,
        "AccentPurple" to ObsidianColors.AccentPurple,
        "TextPrimary" to ObsidianColors.TextPrimary,
        "TextSecondary" to ObsidianColors.TextSecondary,
        "TextMuted" to ObsidianColors.TextMuted,
        "OnAccent" to ObsidianColors.OnAccent,
        "Success" to ObsidianColors.Success,
        "Warning" to ObsidianColors.Warning,
        "Error" to ObsidianColors.Error,
        "Divider" to ObsidianColors.Divider
    )

    // =========================================================================
    // Tier 1: Pure Achromatic Palette Compliance in Light Mode (Feature 16)
    // =========================================================================

    @Test
    fun testObsidianColors_paperLight_allTokensStrictlyAchromatic() {
        ObsidianColors.currentStyle = HansonUiStyle.AHABOARD_PAPER
        assertFalse(ObsidianColors.isDark)

        val tokens = getAllSemanticColors()
        for ((name, color) in tokens) {
            assertTrue(
                "Paper token '$name' must be achromatic (R=${color.red}, G=${color.green}, B=${color.blue})",
                isAchromatic(color)
            )
            // Neutral zinc saturation is strictly <= 0.20 (max delta between channels is <= 2/255 ≈ 0.008)
            val sat = getHsvSaturation(color)
            assertTrue("Token '$name' saturation must be < 0.20, but was $sat", sat < 0.20f)
        }
    }

    // =========================================================================
    // Tier 1: Pure Achromatic Palette Compliance in Dark Mode (Feature 16)
    // =========================================================================

    @Test
    fun testObsidianColors_carbonDark_allTokensStrictlyAchromatic() {
        ObsidianColors.currentStyle = HansonUiStyle.AHABOARD_CARBON
        assertTrue(ObsidianColors.isDark)

        val tokens = getAllSemanticColors()
        for ((name, color) in tokens) {
            assertTrue(
                "Carbon token '$name' must be achromatic (R=${color.red}, G=${color.green}, B=${color.blue})",
                isAchromatic(color)
            )
            // Neutral zinc saturation is strictly <= 0.20 (max delta between channels is <= 2/255 ≈ 0.008)
            val sat = getHsvSaturation(color)
            assertTrue("Token '$name' saturation must be < 0.20, but was $sat", sat < 0.20f)
        }
    }

    // =========================================================================
    // Tier 1: Status Tokens (Success, Warning, Error) Achromatic Non-Chromatic Rule
    // =========================================================================

    @Test
    fun testStatusTokens_doNotUseChromaticGreenYellowRed() {
        for (style in listOf(HansonUiStyle.AHABOARD_PAPER, HansonUiStyle.AHABOARD_CARBON)) {
            ObsidianColors.currentStyle = style

            val success = ObsidianColors.Success
            val warning = ObsidianColors.Warning
            val error = ObsidianColors.Error

            // Verify Success is NOT chromatic green (G >> R and G >> B)
            val isChromaticGreen = success.green > 0.6f && (success.green - success.red > 0.2f)
            assertFalse("Success token must not be chromatic green in $style", isChromaticGreen)

            // Verify Error is NOT chromatic red (R >> G and R >> B)
            val isChromaticRed = error.red > 0.6f && (error.red - error.green > 0.2f)
            assertFalse("Error token must not be chromatic red in $style", isChromaticRed)

            // Verify Warning is NOT chromatic yellow/amber (R > 0.6 && G > 0.6 && B < 0.3)
            val isChromaticYellow = warning.red > 0.6f && warning.green > 0.6f && (warning.green - warning.blue > 0.3f)
            assertFalse("Warning token must not be chromatic yellow in $style", isChromaticYellow)
        }
    }

    // =========================================================================
    // Tier 1: Theme Toggling & Luminance Contrast Contract (Feature 16)
    // =========================================================================

    @Test
    fun testThemeToggling_invertsLuminanceBetweenPaperAndCarbon() {
        // Light Mode (Paper)
        ObsidianColors.isDark = false
        val lightBg = ObsidianColors.Background
        val lightText = ObsidianColors.TextPrimary

        // In Paper Light, Background is light (>0.9) and Text is dark (<0.1)
        val lightBgLuminance = (lightBg.red + lightBg.green + lightBg.blue) / 3f
        val lightTextLuminance = (lightText.red + lightText.green + lightText.blue) / 3f
        assertTrue("Light background luminance should be > 0.9", lightBgLuminance > 0.9f)
        assertTrue("Light text luminance should be < 0.1", lightTextLuminance < 0.1f)

        // Toggle to Dark Mode (Carbon)
        ObsidianColors.toggleTheme()
        assertTrue(ObsidianColors.isDark)
        assertEquals(HansonUiStyle.AHABOARD_CARBON, ObsidianColors.currentStyle)

        val darkBg = ObsidianColors.Background
        val darkText = ObsidianColors.TextPrimary

        // In Carbon Dark, Background is dark (<0.1) and Text is light (>0.9)
        val darkBgLuminance = (darkBg.red + darkBg.green + darkBg.blue) / 3f
        val darkTextLuminance = (darkText.red + darkText.green + darkText.blue) / 3f
        assertTrue("Dark background luminance should be < 0.1", darkBgLuminance < 0.1f)
        assertTrue("Dark text luminance should be > 0.9", darkTextLuminance > 0.9f)
    }

    @Test
    fun testHansonUiStyle_nextCycle() {
        assertEquals(HansonUiStyle.AHABOARD_CARBON, HansonUiStyle.AHABOARD_PAPER.next())
        assertEquals(HansonUiStyle.AHABOARD_PAPER, HansonUiStyle.AHABOARD_CARBON.next())
    }

    // =========================================================================
    // Tier 1: Defensive Grayscale Filter on Dynamic Web Containers (Feature 17)
    // =========================================================================

    @Test
    fun testDefensiveGrayscaleFilter_presentInWebDefaults() {
        val css = GenerativeUiRendererDefaults.ACHROMATIC_DEFENSIVE_CSS
        assertTrue("CSS must contain -webkit-filter: grayscale(100%)", css.contains("-webkit-filter: grayscale(100%) !important;"))
        assertTrue("CSS must contain filter: grayscale(100%)", css.contains("filter: grayscale(100%) !important;"))
    }

    @Test
    fun testDefensiveGrayscaleFilter_injectedIntoArbitraryExternalHtml() {
        val foreignHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <style>
                .neon-card { background: #FF00FF; color: #00FFFF; }
              </style>
            </head>
            <body>
              <div class="neon-card">Colorful Foreign Content</div>
            </body>
            </html>
        """.trimIndent()

        val instrumented = GenerativeUiRendererDefaults.instrumentHtml(foreignHtml)

        // Verifies the defensive grayscale guard style is inserted
        assertTrue(instrumented.contains("<style id=\"achromatic-guard\">"))
        assertTrue(instrumented.contains("filter: grayscale(100%) !important;"))
        assertTrue(instrumented.contains("Colorful Foreign Content"))
    }

    // =========================================================================
    // Tier 2: Boundary & Corner Cases (Alpha Transparency & Extreme Inputs)
    // =========================================================================

    @Test
    fun testGlassAndDimColors_preserveAchromaticChannelsUnderAlpha() {
        for (style in listOf(HansonUiStyle.AHABOARD_PAPER, HansonUiStyle.AHABOARD_CARBON)) {
            ObsidianColors.currentStyle = style

            val glassWhite = ObsidianColors.GlassWhite
            val accentDim = ObsidianColors.AccentDim
            val accentPurpleDim = ObsidianColors.AccentPurpleDim
            val successDim = ObsidianColors.SuccessDim
            val warningDim = ObsidianColors.WarningDim
            val errorDim = ObsidianColors.ErrorDim
            val dotGrid = ObsidianColors.DotGridColor

            for ((name, c) in listOf(
                "GlassWhite" to glassWhite,
                "AccentDim" to accentDim,
                "AccentPurpleDim" to accentPurpleDim,
                "SuccessDim" to successDim,
                "WarningDim" to warningDim,
                "ErrorDim" to errorDim,
                "DotGridColor" to dotGrid
            )) {
                // Alpha should be non-zero
                assertTrue("$name alpha should be > 0", c.alpha > 0f)
                // RGB channels must still be achromatic
                assertTrue("$name RGB must be achromatic in $style", isAchromatic(c))
            }
        }
    }

    @Test
    fun testAchromaticInstrumentation_handlesFragmentWithoutHtmlOrHead() {
        val snippet = "<p>Simple text snippet</p>"
        val instrumented = GenerativeUiRendererDefaults.instrumentHtml(snippet)
        assertTrue(instrumented.contains("filter: grayscale(100%) !important;"))
        assertTrue(instrumented.contains("Simple text snippet"))
    }
}
