package com.hanson.mobileuse.agent

import com.hanson.mobileuse.ui.components.ConsumerOnboardingLogic
import com.hanson.mobileuse.ui.components.OnboardingAiTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsumerOnboardingWizardTest {

    @Test
    fun testStarterPreferencesAndTracks_arePopulatedAndJargonFree() {
        assertEquals(3, ConsumerOnboardingLogic.TOTAL_STEPS)
        assertTrue("Expected at least 5 starter preference bubbles", ConsumerOnboardingLogic.starterPreferences.size >= 5)
        assertEquals(3, OnboardingAiTrack.entries.size)

        val allCopy = buildString {
            ConsumerOnboardingLogic.starterPreferences.forEach {
                append(it.labelKo).append(' ').append(it.factKo).append(' ')
            }
            OnboardingAiTrack.entries.forEach {
                append(it.titleKo).append(' ').append(it.subtitleKo).append(' ')
            }
        }
        for (jargon in listOf("NPU", "RAW", "normX", "JSON", "ADB")) {
            assertFalse("Onboarding copy must not expose developer jargon '$jargon'", allCopy.contains(jargon))
        }
    }

    @Test
    fun testClassifyCredentialInput_recognizesCloudKeysAndLocalUrls() {
        val (okBlank, _) = ConsumerOnboardingLogic.classifyCredentialInput("   ")
        assertFalse(okBlank)

        val (okShort, _) = ConsumerOnboardingLogic.classifyCredentialInput("abc123")
        assertFalse(okShort)

        val (okGemini, msgGemini) = ConsumerOnboardingLogic.classifyCredentialInput("AIza" + "SyDemoValidKey1234567890")
        assertTrue(okGemini)
        assertTrue(msgGemini.contains("Gemini"))

        val (okAqGemini, msgAqGemini) = ConsumerOnboardingLogic.classifyCredentialInput("AQ." + "DemoStudioKey1234567890abcdef")
        assertTrue(okAqGemini)
        assertTrue(msgAqGemini.contains("Gemini"))

        val (okClaude, msgClaude) = ConsumerOnboardingLogic.classifyCredentialInput("sk-" + "ant-demoKey1234567890")
        assertTrue(okClaude)
        assertTrue(msgClaude.contains("Claude"))

        val (okOpenRouter, msgOr) = ConsumerOnboardingLogic.classifyCredentialInput("sk-" + "or-demoKey1234567890")
        assertTrue(okOpenRouter)
        assertTrue(msgOr.contains("OpenRouter"))

        val (okUrl, msgUrl) = ConsumerOnboardingLogic.classifyCredentialInput("http://127.0.0.1:18889/v1")
        assertTrue(okUrl)
        assertTrue(msgUrl.contains("주소"))

        // Placeholder / ellipsis inputs must be rejected
        assertFalse(ConsumerOnboardingLogic.classifyCredentialInput("AIzaSy...placeholder").first)
        assertFalse(ConsumerOnboardingLogic.classifyCredentialInput("your-api-key-here").first)
    }

    @Test
    fun testReadinessScoring_rewardsZeroPermissionInstantExperience() {
        assertEquals(1, ConsumerOnboardingLogic.readinessScore(false, false, false, false))
        assertEquals(2, ConsumerOnboardingLogic.readinessScore(true, false, false, false))
        assertEquals(3, ConsumerOnboardingLogic.readinessScore(true, true, false, false))
        assertEquals(4, ConsumerOnboardingLogic.readinessScore(true, true, true, false))

        assertTrue(ConsumerOnboardingLogic.readinessLabelKo(false, false).contains("바로 체험"))
        assertTrue(ConsumerOnboardingLogic.readinessLabelKo(true, true).contains("모두 준비 완료"))
    }

    @Test
    fun testOnboardingWizardSource_supportsNonBlockingSkipAndRestrictedSettingsGuide() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile ?: cwd
        val src = File(
            root,
            "app/src/main/java/com/hanson/mobileuse/ui/components/ConsumerOnboardingWizard.kt"
        ).readText()
        assertTrue(src.contains("aistudio.google.com/apikey"))
        assertTrue(src.contains("제한된 설정 허용"))
        assertTrue(src.contains("지금은 건너뛰고 바로 시작"))
        assertTrue(src.contains("cli-subscription-bridge"))
        assertTrue(src.contains("ACTION_MANAGE_OVERLAY_PERMISSION"))
    }
}
