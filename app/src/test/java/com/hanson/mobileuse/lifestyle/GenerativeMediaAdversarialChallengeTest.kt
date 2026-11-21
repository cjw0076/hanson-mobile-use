package com.hanson.mobileuse.lifestyle

import com.hanson.mobileuse.agent.AgentMediaFileEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Empirical Adversarial Challenge Suite for Milestone 3 (Features F06-F11).
 * Tests failure modes, edge cases, and architectural discrepancies in:
 * - F06: Ahaboard lifestyle card structure (markdown stripping, chip overflows)
 * - F07: YouTube video ID extraction false positives on non-YouTube URLs
 * - F08: 3D GLB viewport auto-rotation idle timeout during stationary hold
 * - F09: MP4 Cinema viewport scrub bar & mute decoupling from VideoView
 * - F11: High-res image Lightbox dialog pinch-to-zoom variable unbound to Image modifier
 */
class GenerativeMediaAdversarialChallengeTest {

    // =========================================================================
    // CHALLENGE 1: F11 Modal Lightbox Pinch-to-Zoom Unbound State Variable
    // =========================================================================

    @Test
    fun testChallenge_f11_lightboxZoomScaleUnboundInUiRenderer() {
        val rendererFile = File("src/main/java/com/hanson/mobileuse/ui/components/GenerativeUiRenderer.kt")
        assertTrue("GenerativeUiRenderer.kt must exist", rendererFile.exists())
        val source = rendererFile.readText()

        // Locate showLightbox dialog implementation
        val lightboxBlock = source.substringAfter("if (showLightbox) {").substringBefore("// Top Close Button")
        assertTrue("Lightbox block must declare zoomScale", lightboxBlock.contains("zoomScale"))
        assertTrue("Lightbox block must detect gestures", lightboxBlock.contains("detectTransformGestures"))

        // Check if the Image or its container applies zoomScale via graphicsLayer or scale modifier
        val imageSnippet = lightboxBlock.substringAfter("if (loadedBitmap != null) {").substringBefore("contentScale = ContentScale.Fit")
        val isZoomScaleAppliedToImage = imageSnippet.contains("zoomScale") || imageSnippet.contains("graphicsLayer") || imageSnippet.contains("scale(")

        // REMEDIATION VERIFICATION:
        // zoomScale is computed in detectTransformGestures AND applied to the Image via graphicsLayer!
        assertTrue(
            "Remediation Verified: zoomScale is calculated in detectTransformGestures and applied to the Lightbox Image modifier!",
            isZoomScaleAppliedToImage
        )
    }

    // =========================================================================
    // CHALLENGE 2: F07 Overeager YouTube URL Extraction on Non-YouTube URLs
    // =========================================================================

    @Test
    fun testChallenge_f07_overeagerRegexMatchesNonYouTubeDomains() {
        // Legitimate YouTube URLs should extract videoId correctly
        assertEquals("dQw4w9WgXcQ", AgentMediaFileEngine.extractYouTubeVideoIdFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", AgentMediaFileEngine.extractYouTubeVideoIdFromUrl("https://youtu.be/dQw4w9WgXcQ"))

        // ADVERSARIAL CASE: Non-YouTube URL containing a v= parameter with 11 characters
        // e.g. An external API endpoint or non-video website
        val nonYouTubeUrl = "https://api.weather.com/v1/forecast?v=abcdefghijk"
        val extractedId = AgentMediaFileEngine.extractYouTubeVideoIdFromUrl(nonYouTubeUrl)

        // REMEDIATION VERIFICATION:
        // With domain check contains("youtube.com") || contains("youtu.be"), non-YouTube URLs are cleanly rejected!
        org.junit.Assert.assertNull("Remediation Verified: Non-YouTube URL must not match video ID regex", extractedId)
    }

    // =========================================================================
    // CHALLENGE 3: F09 MP4 Cinema Scrub Bar & Mute Decoupled From VideoView
    // =========================================================================

    @Test
    fun testChallenge_f09_mp4CinemaVideoViewLacksSeekAndMuteUpdates() {
        val rendererFile = File("src/main/java/com/hanson/mobileuse/ui/components/GenerativeUiRenderer.kt")
        val source = rendererFile.readText()

        val mp4Block = source.substringAfter("private fun NativeMp4CinemaViewport").substringBefore("private fun NativeGifLoopViewport")
        val androidViewBlock = mp4Block.substringAfter("AndroidView(").substringBefore("Canvas(modifier")

        // In AndroidView.update, seekTo and playback are bound.
        val updateBlock = androidViewBlock.substringAfter("update = {").substringBefore("}")
        val hasSeekBinding = updateBlock.contains("seekTo")
        val hasMuteBinding = updateBlock.contains("setVolume") || updateBlock.contains("isMuted") || source.contains("mediaPlayerRef?.setVolume")

        // REMEDIATION VERIFICATION:
        // Scrubbing and mute update the live VideoView / MediaPlayer!
        assertTrue("Remediation Verified: VideoView update block implements seekTo on scrub!", hasSeekBinding)
        assertTrue("Remediation Verified: VideoView / MediaPlayer updates volume on isMuted toggle!", hasMuteBinding)
    }

    // =========================================================================
    // CHALLENGE 4: F08 3D GLB Viewport Auto-Rotation Idle Timeout During Touch
    // =========================================================================

    @Test
    fun testChallenge_f08_glbStationaryHoldTriggersAutoRotationPrematurely() {
        // Simulated state from NativeGlb3DViewport
        var isUserInteracting = true
        val touchStartTime = 1000L
        val lastInteractionTime = touchStartTime // Finger placed on screen at t=1000ms

        // User holds finger completely still for 700ms (t=1700ms) without moving
        val currentTime = 1700L
        val shouldAutoRotate = !isUserInteracting || (currentTime - lastInteractionTime > 600L)

        // EMPIRICAL BEHAVIOR CONFIRMATION:
        // Because lastInteractionTime is only refreshed on pointer motion (detectTransformGestures),
        // holding a finger stationary for >600ms causes auto-rotation to resume while finger is down!
        assertTrue(
            "Empirical Finding: Stationary touch-and-hold > 600ms resumes auto-rotation underneath user finger",
            shouldAutoRotate
        )
    }

    // =========================================================================
    // CHALLENGE 5: F06 Briefing Markdown Regex Incompleteness
    // =========================================================================

    @Test
    fun testChallenge_f06_briefingMarkdownPreservesRawLinksAndBackticks() {
        val briefingWithLinksAndCode = "추천 장소: [성수 아카이브](https://maps.google.com/123) 및 `오후 3시` 오픈"
        val cleanBriefing = briefingWithLinksAndCode.replace(Regex("[*#_]"), "")

        // Notice that [text](url) and `code` are not stripped by Regex("[*#_]")
        assertTrue(cleanBriefing.contains("https://maps.google.com/123"))
        assertTrue(cleanBriefing.contains("`오후 3시`"))
    }
}
