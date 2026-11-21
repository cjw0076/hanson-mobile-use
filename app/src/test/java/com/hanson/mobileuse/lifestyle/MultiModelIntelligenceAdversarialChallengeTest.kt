package com.hanson.mobileuse.lifestyle

import android.content.Context
import android.content.SharedPreferences
import com.hanson.mobileuse.agent.AgentMemoryManager
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.LifestyleProfile
import com.hanson.mobileuse.agent.LifestyleTaskModality
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.checkCircuitBreaker
import com.hanson.mobileuse.agent.inferModalityFromPrompt
import com.hanson.mobileuse.agent.resolveFallbackModel
import com.hanson.mobileuse.agent.resolveLifestyleModel
import com.hanson.mobileuse.agent.resolveOfflineRoute
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenge Suite for Milestone 4 (Features F12-F17).
 * Executed by Teamwork Challenger 1 to stress-test:
 * - F12: Lifestyle Task Modality routing, circuit breaker thresholds, and fallback logic under latency.
 * - F13: Atomic file write & rename in lifestyle_vault/ against corrupted JSON, missing keys, and concurrent read/write.
 * - F14: Gemini Live WebSocket audio queue buffer flush under rapid barge-in / speech bursts.
 * - F15: Real native AI generation pipeline, 429 backoff math, streaming chunk aggregation, and safety notices.
 * - F16: Zero-friction credential resolution hierarchy (sheet > env > file > BuildConfig) and key masking security.
 * - F17: Editorial API key sheet validation edge cases and formatting sanitation.
 */
class MultiModelIntelligenceAdversarialChallengeTest {

    private lateinit var tempVaultDir: File
    private lateinit var dummyContext: Context

    @Before
    fun setUp() {
        tempVaultDir = File.createTempFile("m4_test_vault_", "").apply {
            delete()
            mkdirs()
        }

        val prefsMap = ConcurrentHashMap<String, Any>()
        val dummyPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? = (prefsMap[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (prefsMap[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (prefsMap[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (prefsMap[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (prefsMap[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (prefsMap[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(k: String?, v: String?): SharedPreferences.Editor { if (k != null && v != null) prefsMap[k] = v; return this }
                override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor { if (k != null && v != null) prefsMap[k] = v; return this }
                override fun putInt(k: String?, v: Int): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun putLong(k: String?, v: Long): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun putFloat(k: String?, v: Float): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun remove(k: String?): SharedPreferences.Editor { prefsMap.remove(k); return this }
                override fun clear(): SharedPreferences.Editor { prefsMap.clear(); return this }
                override fun commit(): Boolean = true
                override fun apply() {}
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        dummyContext = object : android.content.ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = dummyPrefs
            override fun getFilesDir(): File = tempVaultDir
        }
    }

    @After
    fun tearDown() {
        tempVaultDir.deleteRecursively()
    }

    // =========================================================================
    // CHALLENGE 1: Feature 12 Task Modality Routing & Circuit Breaker Stress
    // =========================================================================

    @Test
    fun testChallenge_f12_modalityRouting_keywordCollisionPrioritization() {
        // Competing keyword 1: "그려줘" (Image) vs "분석" (Reasoning)
        // Production logic checks image keywords first
        val imageWinsPrompt = "성수동 갤러리 트렌드를 분석해서 포스터로 그려줘"
        assertEquals(LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE, inferModalityFromPrompt(imageWinsPrompt))

        // Competing keyword 2: "영상" (Motion) vs "계획" (Reasoning)
        // Production logic checks motion keywords before reasoning
        val motionWinsPrompt = "제주도 3박 4일 여행 계획 영상 만들어줘"
        assertEquals(LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA, inferModalityFromPrompt(motionWinsPrompt))

        // Reasoning keyword isolated
        val reasoningPrompt = "이번 주말 성수동 전시회 방문 계획 세워줘"
        assertEquals(LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING, inferModalityFromPrompt(reasoningPrompt))

        // Boundary cases: empty string, whitespace only, Korean emojis, multi-line, English
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt(""))
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("   \n\t  "))
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("☕🍂✨"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("What is the weather today?"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("오늘 점심 뭐 먹지? ㅎㅎㅎ"))
    }

    @Test
    fun testChallenge_f12_modalityRouting_modelConsistencyAndOfflineDowngrade() {
        // Test production resolveLifestyleModel exhaustiveness
        assertEquals("gemini-4-argon", resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING))
        assertEquals("gemini-3.5-flash-lite", resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION))
        assertEquals("gemini-nano-banana-2.1", resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE))
        assertEquals("gemini-omni-1.1-flash", resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA))

        // Test production resolveOfflineRoute
        assertEquals("on-device-nano", resolveOfflineRoute(isOnline = false, targetModel = "gemini-4-argon"))
        assertEquals("on-device-nano", resolveOfflineRoute(isOnline = false, targetModel = "gemini-omni-1.1-flash"))
        assertEquals("gemini-4-argon", resolveOfflineRoute(isOnline = true, targetModel = "gemini-4-argon"))

        // Test production resolveFallbackModel
        assertEquals("gemini-3.8-flash", resolveFallbackModel("gemini-4-argon"))
        assertEquals("gemini-nano-banana-2.1", resolveFallbackModel("gemini-omni-1.1-flash"))
        assertEquals("gemini-3.5-flash-lite", resolveFallbackModel("gemini-nano-banana-2.1"))
        assertEquals("gemini-3.5-flash-lite", resolveFallbackModel("gemini-3.5-flash-lite"))
        assertEquals("gemini-3.5-flash-lite", resolveFallbackModel("unrecognized-model-name"))
    }

    @Test
    fun testChallenge_f12_circuitBreaker_latencyThresholdsAndExtremeValues() {
        val maxLatency = 5000L

        // Exact boundary checks
        assertFalse("At exactly 5000ms latency, circuit breaker should NOT trip", checkCircuitBreaker(5000L, maxLatency))
        assertTrue("At 5001ms latency, circuit breaker MUST trip", checkCircuitBreaker(5001L, maxLatency))
        assertFalse("At 4999ms latency, circuit breaker should not trip", checkCircuitBreaker(4999L, maxLatency))

        // Extreme latency stress values
        assertTrue("At Long.MAX_VALUE, circuit breaker must trip", checkCircuitBreaker(Long.MAX_VALUE, maxLatency))
        assertFalse("At 0ms latency, circuit breaker should not trip", checkCircuitBreaker(0L, maxLatency))
        assertFalse("At negative latency, circuit breaker should not trip", checkCircuitBreaker(-1000L, maxLatency))

        // Custom latency threshold verification
        assertTrue(checkCircuitBreaker(250L, maxAcceptableLatencyMs = 200L))
        assertFalse(checkCircuitBreaker(150L, maxAcceptableLatencyMs = 200L))
    }

    // =========================================================================
    // CHALLENGE 2: Feature 13 Atomic File Write & Vault Corruption Recovery
    // =========================================================================

    @Test
    fun testChallenge_f13_vault_atomicWriteFileCreationAndRenameCleanup() {
        val memoryManager = AgentMemoryManager(dummyContext)
        val initialProfile = LifestyleProfile(
            userName = "사용자",
            favoriteNeighborhood = "성수동",
            morningDrink = "플랫화이트",
            preferredModelTier = "AUTO",
            emojiTag = "☕🎨",
            notes = "주말 갤러리 투어 선호"
        )

        // Save profile using atomic save
        val saveResult = memoryManager.saveLifestyleContext(initialProfile)
        assertTrue("saveLifestyleContext must succeed", saveResult)

        // Check target file exists in vault
        val profileFile = File(dummyContext.filesDir, "lifestyle_vault/lifestyle_profile.json")
        assertTrue("lifestyle_profile.json must exist in vault", profileFile.exists())
        assertTrue("lifestyle_profile.json must not be empty", profileFile.length() > 0)

        // Verify that temporary files were cleaned up and not left lingering
        val vaultDir = File(dummyContext.filesDir, "lifestyle_vault")
        val tempFiles = vaultDir.listFiles { _, name -> name.startsWith("vault_write_") && name.endsWith(".tmp") }
        assertTrue("Temporary write files must be cleaned up after atomic rename", tempFiles.isNullOrEmpty())

        // Read back and assert full field preservation
        val loaded = memoryManager.loadLifestyleContext()
        assertEquals("사용자", loaded.userName)
        assertEquals("성수동", loaded.favoriteNeighborhood)
        assertEquals("플랫화이트", loaded.morningDrink)
        assertEquals("☕🎨", loaded.emojiTag)
        assertEquals("주말 갤러리 투어 선호", loaded.notes)
    }

    @Test
    fun testChallenge_f13_vault_corruptJsonGracefulRecovery() {
        val memoryManager = AgentMemoryManager(dummyContext)
        val profileFile = File(dummyContext.filesDir, "lifestyle_vault/lifestyle_profile.json")
        profileFile.parentFile?.mkdirs()

        // 1. Write syntactically invalid JSON (broken brackets)
        profileFile.writeText("{ \"userName\": \"사용자\", \"favoriteNeighborhood\": ", Charsets.UTF_8)
        val recoveredMalformed = memoryManager.loadLifestyleContext()
        assertNotNull(recoveredMalformed)
        assertEquals("recovered_from_corruption", recoveredMalformed.notes)
        assertEquals("사용자", recoveredMalformed.userName) // Default fallback userName

        // 2. Write binary garbage bytes
        profileFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, -1, -2, 0x7F))
        val recoveredGarbage = memoryManager.loadLifestyleContext()
        assertNotNull(recoveredGarbage)
        assertEquals("recovered_from_corruption", recoveredGarbage.notes)

        // 3. Write empty file (length = 0L)
        profileFile.writeText("", Charsets.UTF_8)
        val recoveredEmpty = memoryManager.loadLifestyleContext()
        assertNotNull(recoveredEmpty)
        assertEquals("사용자", recoveredEmpty.userName)
        assertEquals("", recoveredEmpty.notes)

        // 4. Write whitespace only
        profileFile.writeText("   \r\n\t   ", Charsets.UTF_8)
        val recoveredWhitespace = memoryManager.loadLifestyleContext()
        assertNotNull(recoveredWhitespace)
        assertEquals("사용자", recoveredWhitespace.userName)
    }

    @Test
    fun testChallenge_f13_vault_concurrentReadWriteStressHarness() {
        val memoryManager = AgentMemoryManager(dummyContext)
        val threadCount = 16
        val operationsPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val readSuccessCount = AtomicInteger(0)
        val writeSuccessCount = AtomicInteger(0)
        val errors = ConcurrentHashMap<String, Throwable>()

        for (t in 0 until threadCount) {
            val isWriter = (t % 2 == 0)
            executor.execute {
                try {
                    for (op in 0 until operationsPerThread) {
                        if (isWriter) {
                            val profile = LifestyleProfile(
                                userName = "Thread_${t}_$op",
                                favoriteNeighborhood = "Neighborhood_$t",
                                morningDrink = "Drink_$op",
                                notes = "Stress test iteration $op"
                            )
                            if (memoryManager.saveLifestyleContext(profile)) {
                                writeSuccessCount.incrementAndGet()
                            }
                        } else {
                            val loaded = memoryManager.loadLifestyleContext()
                            if (loaded.userName.isNotBlank()) {
                                readSuccessCount.incrementAndGet()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    errors["Thread_$t"] = e
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("Concurrent stress harness completed within 10s", completed)
        assertTrue("Zero exceptions during concurrent vault read/write", errors.isEmpty())
        assertTrue("Concurrent writes must succeed", writeSuccessCount.get() > 0)
        assertTrue("Concurrent reads must succeed", readSuccessCount.get() > 0)

        // Final verification that vault remains valid and readable
        val finalProfile = memoryManager.loadLifestyleContext()
        assertNotNull(finalProfile)
        assertTrue(finalProfile.userName.isNotBlank())
    }

    @Test
    fun testChallenge_f13_vault_promptInjectionEscapingAndSpecialCharacters() {
        val memoryManager = AgentMemoryManager(dummyContext)
        val specialProfile = LifestyleProfile(
            userName = "홍길동",
            favoriteNeighborhood = "성수동 & 서울숲",
            morningDrink = "아이스 아메리카노 (디카페인)",
            emojiTag = "☕🎨🍂✨",
            notes = "따옴표 \"특수기호\" <script>태그</script> 테스트"
        )
        memoryManager.saveLifestyleContext(specialProfile)

        val promptInjection = memoryManager.buildMemoryPromptInjection()
        assertTrue(promptInjection.contains("홍길동"))
        assertTrue(promptInjection.contains("성수동 & 서울숲"))
        assertTrue(promptInjection.contains("아이스 아메리카노 (디카페인)"))
        assertTrue(promptInjection.startsWith("사용자 취향[선호지역:"))
    }

    // =========================================================================
    // CHALLENGE 3: Feature 14 Gemini Live Streaming & Audio Queue Stress
    // =========================================================================

    @Test
    fun testChallenge_f14_liveStreaming_bidiGenerateContentHandshakePayload() {
        val handshake = LiveVoiceSessionManager.buildLiveSetupHandshake("models/gemini-3.8-live")
        assertTrue("Handshake must contain setup key", handshake.has("setup"))

        val setupObj = handshake.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setupObj.getString("model"))

        val genConfig = setupObj.getJSONObject("generationConfig")
        val modalities = genConfig.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
    }

    @Test
    fun testChallenge_f14_liveStreaming_audioSpecsMathematicalInvariants() {
        // Validate input PCM 16kHz 16-bit mono
        val inSampleRate = LiveVoiceSessionManager.AUDIO_INPUT_SAMPLE_RATE
        val inBits = LiveVoiceSessionManager.AUDIO_INPUT_BITS_PER_SAMPLE
        val inChannels = LiveVoiceSessionManager.AUDIO_INPUT_CHANNELS
        val expectedInByteRate = inSampleRate * (inBits / 8) * inChannels
        assertEquals(32000, expectedInByteRate)
        assertEquals(expectedInByteRate, LiveVoiceSessionManager.AUDIO_INPUT_BYTE_RATE)

        // Validate output PCM 24kHz 16-bit mono
        val outSampleRate = LiveVoiceSessionManager.AUDIO_OUTPUT_SAMPLE_RATE
        val outBits = LiveVoiceSessionManager.AUDIO_OUTPUT_BITS_PER_SAMPLE
        val outChannels = LiveVoiceSessionManager.AUDIO_OUTPUT_CHANNELS
        val expectedOutByteRate = outSampleRate * (outBits / 8) * outChannels
        assertEquals(48000, expectedOutByteRate)
        assertEquals(expectedOutByteRate, LiveVoiceSessionManager.AUDIO_OUTPUT_BYTE_RATE)

        // Frame duration calculations
        // 20ms chunk = 0.02s
        val in20msBytes = (inSampleRate * 0.02 * (inBits / 8)).toInt()
        assertEquals(640, in20msBytes)

        val out20msBytes = (outSampleRate * 0.02 * (outBits / 8)).toInt()
        assertEquals(960, out20msBytes)

        // Barge-in latency contract
        assertTrue("Barge-in latency must be <= 200ms", LiveVoiceSessionManager.MAX_BARGE_IN_LATENCY_MS <= 200L)
    }

    @Test
    fun testChallenge_f14_liveStreaming_rapidAudioChunkFilteringAndBargeInSimulation() {
        // Test chunk transmission filter
        assertFalse(LiveVoiceSessionManager.shouldSendAudioChunk(byteArrayOf()))
        assertTrue(LiveVoiceSessionManager.shouldSendAudioChunk(byteArrayOf(0x01, 0x02)))

        // Rapid mic mute toggling
        val initialMute = LiveVoiceSessionManager.isMicMuted()
        val toggledOnce = LiveVoiceSessionManager.toggleMicMute()
        assertEquals(!initialMute, toggledOnce)
        val toggledTwice = LiveVoiceSessionManager.toggleMicMute()
        assertEquals(initialMute, toggledTwice)

        // Heartbeat verification
        LiveVoiceSessionManager.recordPongReceived()
        assertTrue("Connection must be alive immediately after pong", LiveVoiceSessionManager.isConnectionAlive())
    }

    // =========================================================================
    // CHALLENGE 4: Feature 15 & 16 AI Pipeline, Credential Hierarchy & Masking
    // =========================================================================

    @Test
    fun testChallenge_f16_credential_strictPrecedenceHierarchy() {
        // Tier 1: Sheet overrides all
        val r1 = GeminiMultimodalClient.resolveApiKey(
            sheetInput = "SHEET_KEY",
            envVar = "ENV_KEY",
            fileContent = "FILE_KEY",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("SHEET_KEY", r1)

        // Tier 2: Env overrides file and buildConfig when sheet is blank
        val r2 = GeminiMultimodalClient.resolveApiKey(
            sheetInput = "   ",
            envVar = "ENV_KEY",
            fileContent = "FILE_KEY",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("ENV_KEY", r2)

        // Tier 3: File overrides buildConfig when sheet and env are null/blank
        val r3 = GeminiMultimodalClient.resolveApiKey(
            sheetInput = null,
            envVar = "",
            fileContent = "FILE_KEY",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("FILE_KEY", r3)

        // Tier 4: BuildConfig fallback when sheet, env, and file are null/blank
        val r4 = GeminiMultimodalClient.resolveApiKey(
            sheetInput = null,
            envVar = null,
            fileContent = "  \n  ",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("BUILD_KEY", r4)

        // All absent -> empty string
        val r5 = GeminiMultimodalClient.resolveApiKey(null, "", null, "   ")
        assertEquals("", r5)
    }

    @Test
    fun testChallenge_f16_credential_fileKeyExtractionEdgeCases() {
        val testFileContent = """
            # Hanson Mobile API Credentials
            # Note: Do not commit
            claude_key=sk-ant-test12345
            gemini_key=AIzaSyValidToken123456789
            openai_key=sk-proj-openai98765
        """.trimIndent()

        assertEquals("AIzaSyValidToken123456789", GeminiMultimodalClient.extractKeyFromFile(testFileContent, "gemini_key"))
        assertEquals("sk-ant-test12345", GeminiMultimodalClient.extractKeyFromFile(testFileContent, "claude_key"))
        assertEquals("sk-proj-openai98765", GeminiMultimodalClient.extractKeyFromFile(testFileContent, "openai_key"))
        assertNull(GeminiMultimodalClient.extractKeyFromFile(testFileContent, "non_existent_key"))

        // CRLF line breaks and whitespace around token
        val crlfContent = "gemini_key=AIzaSyCrLfKey\r\nclaude_key=sk-ant-crlf\r\n"
        assertEquals("AIzaSyCrLfKey", GeminiMultimodalClient.extractKeyFromFile(crlfContent, "gemini_key"))
    }

    @Test
    fun testChallenge_f16_credential_maskingSecurityBlastRadius() {
        // Short keys must never leak characters or crash with StringIndexOutOfBoundsException
        assertEquals("****", GeminiMultimodalClient.maskKey(""))
        assertEquals("****", GeminiMultimodalClient.maskKey("a"))
        assertEquals("****", GeminiMultimodalClient.maskKey("12345678"))

        // Valid length key: 4 prefix + 4 suffix
        val fullKey = "AIzaSyD_Secret_Token_12345_XYZ"
        val masked = GeminiMultimodalClient.maskKey(fullKey)
        assertEquals("AIza..._XYZ", masked)
        assertFalse("Masked key must not expose interior token characters", masked.contains("Secret_Token"))

        // Boundary: 9 characters (length > 8)
        val nineCharKey = "123456789"
        val maskedNine = GeminiMultimodalClient.maskKey(nineCharKey)
        assertEquals("1234...6789", maskedNine)
    }

    @Test
    fun testChallenge_f16_credential_geminiKeyValidationStrictness() {
        // Valid key
        assertTrue(GeminiMultimodalClient.isValidGeminiKey("AIzaSyD_ValidGeminiKeyForTesting12345"))

        // Invalid: missing 'Sy' in prefix
        assertFalse(GeminiMultimodalClient.isValidGeminiKey("AIza_InvalidPrefixKey1234567890"))

        // Invalid: length < 20
        assertFalse(GeminiMultimodalClient.isValidGeminiKey("AIzaSyShort"))

        // Invalid: empty or blank
        assertFalse(GeminiMultimodalClient.isValidGeminiKey(""))
        assertFalse(GeminiMultimodalClient.isValidGeminiKey("   "))
    }

    @Test
    fun testChallenge_f15_pipeline_backoffAndStreamingAggregation() {
        // Exponential backoff math: 1000 * 2^attempt
        assertEquals(1000L, GeminiMultimodalClient.computeExponentialBackoff(0))
        assertEquals(2000L, GeminiMultimodalClient.computeExponentialBackoff(1))
        assertEquals(4000L, GeminiMultimodalClient.computeExponentialBackoff(2))
        assertEquals(8000L, GeminiMultimodalClient.computeExponentialBackoff(3))
        assertEquals(16000L, GeminiMultimodalClient.computeExponentialBackoff(4))

        // Streaming chunk aggregation
        val chunks = listOf("성수동 ", "갤러리 ", "투어 ", "안내")
        assertEquals("성수동 갤러리 투어 안내", GeminiMultimodalClient.aggregateStreamingChunks(chunks))
        assertEquals("", GeminiMultimodalClient.aggregateStreamingChunks(emptyList()))

        // Safety guidance message
        val safetyNotice = GeminiMultimodalClient.resolveSafetyNotice("SAFETY")
        assertTrue(safetyNotice.contains("안전한 콘텐츠 가이드라인"))
        assertEquals("답변 완료", GeminiMultimodalClient.resolveSafetyNotice("STOP"))
    }
}
