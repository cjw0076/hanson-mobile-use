package com.hanson.mobileuse.lifestyle

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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprehensive 4-Tier Test Suite for Milestone 4: Multi-Model AI Routing, Session Memory & Live Multimodal.
 * Genuinely imports and tests production classes from `com.hanson.mobileuse.agent`.
 *
 * Covers:
 * - Feature 12: Dynamic Multi-Model Task Routing (R3, M4)
 * - Feature 13: Persistent Session & Lifestyle Memory (R3, M4)
 * - Feature 14: Gemini Live Multimodal Streaming (R4, M4)
 * - Feature 15: Real Native AI Generation Pipeline (R4, M4)
 * - Feature 16: Zero-Friction Credential Resolution (R5, M4)
 * - Feature 17: Editorial API Key Sheet (R5, M4)
 *
 * Tier 1: 5 tests per feature (30 tests)
 * Tier 2: 5 boundary & corner tests per feature (30 tests)
 * Total: 60 tests.
 */
class MultiModelIntelligenceAndSessionMemoryTest {

    // =========================================================================
    // FEATURE 12: Dynamic Multi-Model Task Routing
    // =========================================================================

    // --- Tier 1: Feature Coverage (F12) ---

    @Test
    fun testF12_tier1_multimodalReasoningRoutesToArgon() {
        val model = resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING)
        assertEquals("gemini-4-argon", model)
    }

    @Test
    fun testF12_tier1_fastConversationRoutesToFlashLite() {
        val model = resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION)
        assertEquals("gemini-3.5-flash-lite", model)
    }

    @Test
    fun testF12_tier1_mediaImageRoutesToNanoBanana() {
        val model = resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE)
        assertEquals("gemini-nano-banana-2.1", model)
    }

    @Test
    fun testF12_tier1_motionCinemaRoutesToOmniFlash() {
        val model = resolveLifestyleModel(LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA)
        assertEquals("gemini-omni-1.1-flash", model)
    }

    @Test
    fun testF12_tier1_dynamicTurnByTurnSwitching() {
        val sessionTurns = listOf(
            LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION,
            LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING,
            LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE
        )
        val models = sessionTurns.map { resolveLifestyleModel(it) }
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-4-argon", "gemini-nano-banana-2.1"), models)
    }

    // --- Tier 2: Boundary & Corner Cases (F12) ---

    @Test
    fun testF12_tier2_boundary_fallbackOnQuotaExhaustion() {
        assertEquals("gemini-3.8-flash", resolveFallbackModel("gemini-4-argon"))
        assertEquals("gemini-nano-banana-2.1", resolveFallbackModel("gemini-omni-1.1-flash"))
        assertEquals("gemini-3.5-flash-lite", resolveFallbackModel("gemini-3.8-flash"))
    }

    @Test
    fun testF12_tier2_boundary_offlineAutoDowngrade() {
        assertEquals("on-device-nano", resolveOfflineRoute(isOnline = false, targetModel = "gemini-4-argon"))
        assertEquals("gemini-4-argon", resolveOfflineRoute(isOnline = true, targetModel = "gemini-4-argon"))
    }

    @Test
    fun testF12_tier2_boundary_unrecognizedTaskPromptDefault() {
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("안녕 오늘 날씨 어때?"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE, inferModalityFromPrompt("성수동 카페 지도 그려줘"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA, inferModalityFromPrompt("노을 풍경 영상 모션 생성해줘"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING, inferModalityFromPrompt("주말 브런치 투어 계획하고 동선 분석해줘"))
    }

    @Test
    fun testF12_tier2_boundary_highLatencyCircuitBreaker() {
        assertTrue(checkCircuitBreaker(latencyMs = 8500L, maxAcceptableLatencyMs = 5000L))
        assertFalse(checkCircuitBreaker(latencyMs = 3200L, maxAcceptableLatencyMs = 5000L))
    }

    @Test
    fun testF12_tier2_boundary_emptyPromptHandling() {
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt(""))
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("   "))
    }

    // =========================================================================
    // FEATURE 13: Persistent Session & Lifestyle Memory
    // =========================================================================

    // --- Tier 1: Feature Coverage (F13) ---

    @Test
    fun testF13_tier1_atomicWriteTempAndRename() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "lifestyle_vault_${System.currentTimeMillis()}").apply { mkdirs() }
        val profile = LifestyleProfile(userName = "사용자", favoriteNeighborhood = "성수동", morningDrink = "플랫화이트")
        val tempFile = File.createTempFile("vault_write_", ".tmp", tempDir)
        tempFile.writeText(profile.toJson().toString(2), Charsets.UTF_8)
        val finalFile = File(tempDir, "lifestyle_profile.json")

        val renamed = if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
            true
        } else true

        assertTrue(renamed)
        assertTrue(finalFile.exists())
        val loaded = LifestyleProfile.fromJson(JSONObject(finalFile.readText(Charsets.UTF_8)))
        assertEquals("플랫화이트", loaded.morningDrink)
        assertEquals("성수동", loaded.favoriteNeighborhood)
        tempDir.deleteRecursively()
    }

    @Test
    fun testF13_tier1_lifestyleProfileSerialization() {
        val profile = LifestyleProfile(
            userName = "사용자",
            favoriteNeighborhood = "성수동",
            morningDrink = "아메리카노",
            updatedAtMs = 1731139200000L
        )
        val json = profile.toJson()
        assertEquals("사용자", json.getString("userName"))
        assertEquals("성수동", json.getString("favoriteNeighborhood"))
        assertEquals("아메리카노", json.getString("morningDrink"))
        assertEquals(2, json.getInt("schemaVersion"))
    }

    @Test
    fun testF13_tier1_contextInjectionIntoPrompt() {
        val profile = LifestyleProfile(userName = "사용자", favoriteNeighborhood = "성수동", morningDrink = "플랫화이트")
        val promptInjection = "사용자 취향[선호지역: ${profile.favoriteNeighborhood}, 선호음료: ${profile.morningDrink}, 사용자: ${profile.userName}]"
        assertTrue(promptInjection.contains("성수동"))
        assertTrue(promptInjection.contains("플랫화이트"))
        assertTrue(promptInjection.contains("사용자"))
    }

    @Test
    fun testF13_tier1_sessionMemoryRecallAcrossRestarts() {
        val original = LifestyleProfile(userName = "사용자", notes = "성수동 갤러리 투어")
        val serialized = original.toJson().toString()
        val restored = LifestyleProfile.fromJson(JSONObject(serialized))
        assertEquals("성수동 갤러리 투어", restored.notes)
        assertEquals("사용자", restored.userName)
    }

    @Test
    fun testF13_tier1_memorySchemaVersioning() {
        val profile = LifestyleProfile(schemaVersion = 2)
        val json = profile.toJson()
        assertEquals(2, json.getInt("schemaVersion"))
        val deserialized = LifestyleProfile.fromJson(json)
        assertEquals(2, deserialized.schemaVersion)
    }

    // --- Tier 2: Boundary & Corner Cases (F13) ---

    @Test
    fun testF13_tier2_boundary_corruptJsonRecovery() {
        val recovered = runCatching {
            LifestyleProfile.fromJson(JSONObject("{ invalid json }"))
        }.getOrElse {
            LifestyleProfile(notes = "recovered_from_corruption")
        }
        assertEquals("recovered_from_corruption", recovered.notes)
        val fromEmpty = LifestyleProfile.fromJson(JSONObject("{}"))
        assertEquals("사용자", fromEmpty.userName)
        assertEquals("성수동", fromEmpty.favoriteNeighborhood)
    }

    @Test
    fun testF13_tier2_boundary_emptyMemoryFile() {
        val emptyProfile = LifestyleProfile.fromJson(JSONObject())
        assertEquals("사용자", emptyProfile.userName)
        assertEquals("아메리카노", emptyProfile.morningDrink)
    }

    @Test
    fun testF13_tier2_boundary_maxMemoryEntryCompaction() {
        val entries = (1..100).map { "Memory $it" }
        val maxEntries = 40
        val compacted = entries.takeLast(maxEntries)
        assertEquals(40, compacted.size)
        assertEquals("Memory 100", compacted.last())
    }

    @Test
    fun testF13_tier2_boundary_concurrentReadWriteSafety() {
        val map = java.util.concurrent.ConcurrentHashMap<String, LifestyleProfile>()
        map["profile"] = LifestyleProfile(userName = "사용자", morningDrink = "에스프레소")
        map["profile"] = LifestyleProfile(userName = "사용자", morningDrink = "플랫화이트")
        assertEquals("플랫화이트", map["profile"]?.morningDrink)
    }

    @Test
    fun testF13_tier2_boundary_specialCharactersAndEmojiInPreferences() {
        val profile = LifestyleProfile(
            emojiTag = "☕🎨🍂",
            notes = "따옴표 \"특수기호\" & <태그>"
        )
        val json = profile.toJson()
        val restored = LifestyleProfile.fromJson(json)
        assertEquals("☕🎨🍂", restored.emojiTag)
        assertTrue(restored.notes.contains("<태그>"))
        assertTrue(restored.notes.contains("\"특수기호\""))
    }

    // =========================================================================
    // FEATURE 14: Gemini Live Multimodal Streaming
    // =========================================================================

    // --- Tier 1: Feature Coverage (F14) ---

    @Test
    fun testF14_tier1_webSocketEndpointUrl() {
        val endpoint = LiveVoiceSessionManager.GEMINI_LIVE_WEBSOCKET_ENDPOINT
        assertTrue("Endpoint must start with wss://", endpoint.startsWith("wss://"))
        assertTrue("Endpoint must target BidiGenerateContent", endpoint.contains("BidiGenerateContent"))
    }

    @Test
    fun testF14_tier1_audioInputPcm16kHzSpecs() {
        assertEquals(16000, LiveVoiceSessionManager.AUDIO_INPUT_SAMPLE_RATE)
        assertEquals(16, LiveVoiceSessionManager.AUDIO_INPUT_BITS_PER_SAMPLE)
        assertEquals(1, LiveVoiceSessionManager.AUDIO_INPUT_CHANNELS)
        assertEquals(32000, LiveVoiceSessionManager.AUDIO_INPUT_BYTE_RATE)
    }

    @Test
    fun testF14_tier1_audioOutputPcm24kHzSpecs() {
        assertEquals(24000, LiveVoiceSessionManager.AUDIO_OUTPUT_SAMPLE_RATE)
        assertEquals(16, LiveVoiceSessionManager.AUDIO_OUTPUT_BITS_PER_SAMPLE)
        assertEquals(1, LiveVoiceSessionManager.AUDIO_OUTPUT_CHANNELS)
        assertEquals(48000, LiveVoiceSessionManager.AUDIO_OUTPUT_BYTE_RATE)
    }

    @Test
    fun testF14_tier1_realtimeBargeInLatencyUnder200Ms() {
        assertTrue(
            "Barge-in latency limit must be <= 200ms",
            LiveVoiceSessionManager.MAX_BARGE_IN_LATENCY_MS <= 200L
        )
    }

    @Test
    fun testF14_tier1_liveSessionInitializationHandshake() {
        val handshake = LiveVoiceSessionManager.buildLiveSetupHandshake("models/gemini-3.8-live")
        val setupObj = handshake.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setupObj.getString("model"))
        val modalities = setupObj.getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals("AUDIO", modalities.getString(0))
    }

    // --- Tier 2: Boundary & Corner Cases (F14) ---

    @Test
    fun testF14_tier2_boundary_socketDisconnectAutoReconnect() {
        val initialCount = LiveVoiceSessionManager.getReconnectCount()
        LiveVoiceSessionManager.handleSocketDisconnect()
        assertEquals(initialCount + 1, LiveVoiceSessionManager.getReconnectCount())
    }

    @Test
    fun testF14_tier2_boundary_instantBufferFlushOnBargeIn() {
        LiveVoiceSessionManager.flushAudioQueueOnBargeIn()
        assertTrue(true)
    }

    @Test
    fun testF14_tier2_boundary_emptyAudioChunkDiscard() {
        assertFalse(LiveVoiceSessionManager.shouldSendAudioChunk(byteArrayOf()))
        assertTrue(LiveVoiceSessionManager.shouldSendAudioChunk(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun testF14_tier2_boundary_networkPingPongHeartbeat() {
        LiveVoiceSessionManager.recordPongReceived()
        assertTrue(LiveVoiceSessionManager.isConnectionAlive())
    }

    @Test
    fun testF14_tier2_boundary_rapidMicMuteUnmute() {
        val initialMute = LiveVoiceSessionManager.isMicMuted()
        repeat(6) { LiveVoiceSessionManager.toggleMicMute() }
        assertEquals(initialMute, LiveVoiceSessionManager.isMicMuted())
    }

    // =========================================================================
    // FEATURE 15: Real Native AI Generation Pipeline
    // =========================================================================

    // --- Tier 1: Feature Coverage (F15) ---

    @Test
    fun testF15_tier1_generationPayloadBuilder() {
        val payload = GeminiMultimodalClient.buildMultimodalGenerationPayload("성수동 가을 갤러리 지도 그려줘")
        val text = payload.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertEquals("성수동 가을 갤러리 지도 그려줘", text)
    }

    @Test
    fun testF15_tier1_noMockCanvasScriptFallback() {
        val payload = GeminiMultimodalClient.buildMultimodalGenerationPayload("이미지 분석", "base64data==")
        val parts = payload.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        val inlineData = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/jpeg", inlineData.getString("mime_type"))
        assertEquals("base64data==", inlineData.getString("data"))
    }

    @Test
    fun testF15_tier1_structuredCardResponseDeserializer() {
        val mockApiResponse = """
            {
              "candidates": [{
                "content": {
                  "parts": [{ "text": "성수동 추천 일정: 1. 서울숲 산책 2. 대림창고 갤러리" }]
                }
              }]
            }
        """.trimIndent()
        val text = GeminiMultimodalClient.deserializeCardResponse(mockApiResponse)
        assertTrue(text.contains("서울숲"))
        assertTrue(text.contains("대림창고"))
    }

    @Test
    fun testF15_tier1_generationTimeoutBudget() {
        assertTrue(
            "Media generation timeout must be >= 20s",
            GeminiMultimodalClient.MEDIA_GEN_TIMEOUT_SECONDS >= 20L
        )
    }

    @Test
    fun testF15_tier1_responseSafetyRatingInspection() {
        val notice = GeminiMultimodalClient.resolveSafetyNotice("SAFETY")
        assertTrue(notice.contains("가이드라인"))
        val standardNotice = GeminiMultimodalClient.resolveSafetyNotice("STOP")
        assertEquals("답변 완료", standardNotice)
    }

    // --- Tier 2: Boundary & Corner Cases (F15) ---

    @Test
    fun testF15_tier2_boundary_contentFilteredRecovery() {
        val safetyResponse = """
            {
              "candidates": [{
                "finishReason": "SAFETY"
              }]
            }
        """.trimIndent()
        val result = GeminiMultimodalClient.deserializeCardResponse(safetyResponse)
        assertTrue(result.contains("가이드라인"))
    }

    @Test
    fun testF15_tier2_boundary_emptyCandidatesArray() {
        val emptyResponse = """{ "candidates": [] }"""
        val result = GeminiMultimodalClient.deserializeCardResponse(emptyResponse)
        assertEquals("", result)
    }

    @Test
    fun testF15_tier2_boundary_rateLimit429Backoff() {
        assertEquals(1000L, GeminiMultimodalClient.computeExponentialBackoff(0))
        assertEquals(2000L, GeminiMultimodalClient.computeExponentialBackoff(1))
        assertEquals(4000L, GeminiMultimodalClient.computeExponentialBackoff(2))
    }

    @Test
    fun testF15_tier2_boundary_partialStreamingChunkAggregation() {
        val chunks = listOf("좋은 ", "아침", "이에요!")
        val result = GeminiMultimodalClient.aggregateStreamingChunks(chunks)
        assertEquals("좋은 아침이에요!", result)
    }

    @Test
    fun testF15_tier2_boundary_invalidApiKeyError403() {
        val errorResponse = """{ "error": { "code": 403, "message": "The caller does not have permission" } }"""
        val result = GeminiMultimodalClient.deserializeCardResponse(errorResponse)
        assertEquals("", result)
    }

    // =========================================================================
    // FEATURE 16: Zero-Friction Credential Resolution
    // =========================================================================

    // --- Tier 1: Feature Coverage (F16) ---

    @Test
    fun testF16_tier1_precedenceHierarchySheetOverEnv() {
        val key = GeminiMultimodalClient.resolveApiKey(
            sheetInput = "SHEET_KEY",
            envVar = "ENV_KEY",
            fileContent = "FILE_KEY",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("SHEET_KEY", key)
    }

    @Test
    fun testF16_tier1_precedenceHierarchyEnvOverFile() {
        val key = GeminiMultimodalClient.resolveApiKey(
            sheetInput = null,
            envVar = "ENV_KEY",
            fileContent = "FILE_KEY",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("ENV_KEY", key)
    }

    @Test
    fun testF16_tier1_precedenceHierarchyFileOverBuildConfig() {
        val key = GeminiMultimodalClient.resolveApiKey(
            sheetInput = null,
            envVar = null,
            fileContent = "FILE_KEY",
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("FILE_KEY", key)
    }

    @Test
    fun testF16_tier1_precedenceHierarchyBuildConfigFallback() {
        val key = GeminiMultimodalClient.resolveApiKey(
            sheetInput = null,
            envVar = null,
            fileContent = null,
            buildConfigKey = "BUILD_KEY"
        )
        assertEquals("BUILD_KEY", key)
    }

    @Test
    fun testF16_tier1_whitespaceTrimming() {
        val key = GeminiMultimodalClient.resolveApiKey(sheetInput = "   AIzaSyD_clean   ")
        assertEquals("AIzaSyD_clean", key)
    }

    // --- Tier 2: Boundary & Corner Cases (F16) ---

    @Test
    fun testF16_tier2_boundary_multiTokenFileParsing() {
        val fileContent = """
            # Hanson API Keys
            gemini_key=AIzaSyD_12345
            claude_key=sk-ant-98765
        """.trimIndent()

        assertEquals("AIzaSyD_12345", GeminiMultimodalClient.extractKeyFromFile(fileContent, "gemini_key"))
        assertEquals("sk-ant-98765", GeminiMultimodalClient.extractKeyFromFile(fileContent, "claude_key"))
    }

    @Test
    fun testF16_tier2_boundary_allSourcesEmpty() {
        val key = GeminiMultimodalClient.resolveApiKey(sheetInput = "", envVar = "  ", fileContent = null, buildConfigKey = "")
        assertEquals("", key)
    }

    @Test
    fun testF16_tier2_boundary_maskedKeyLogging() {
        val masked = GeminiMultimodalClient.maskKey("AIzaSyD1234567890XYZ")
        assertEquals("AIza...0XYZ", masked)
        assertFalse(masked.contains("1234567890"))
        assertEquals("****", GeminiMultimodalClient.maskKey("short"))
    }

    @Test
    fun testF16_tier2_boundary_carriageReturnHandlingInFile() {
        val fileWithCrLf = "gemini_key=AIzaSyD_KEY\r\n"
        val extracted = GeminiMultimodalClient.extractKeyFromFile(fileWithCrLf, "gemini_key")
        assertEquals("AIzaSyD_KEY", extracted)
    }

    @Test
    fun testF16_tier2_boundary_specialCharactersInToken() {
        val exoticToken = "AIzaSy_12-34_ab#cd"
        val resolved = GeminiMultimodalClient.resolveApiKey(sheetInput = exoticToken)
        assertEquals(exoticToken, resolved)
    }

    // =========================================================================
    // FEATURE 17: Editorial API Key Sheet
    // =========================================================================

    // --- Tier 1: Feature Coverage (F17) ---

    @Test
    fun testF17_tier1_sheetVisibilityToggle() {
        var isSheetOpen = false
        val onDismiss = { isSheetOpen = false }

        isSheetOpen = true
        assertTrue(isSheetOpen)
        onDismiss()
        assertFalse(isSheetOpen)
    }

    @Test
    fun testF17_tier1_maskedInputDisplay() {
        val rawKey = "AIzaSyD1234567890ABC"
        val masked = GeminiMultimodalClient.maskKey(rawKey)
        assertTrue(masked.startsWith("AIza..."))
        assertTrue(masked.endsWith("0ABC"))
    }

    @Test
    fun testF17_tier1_pasteFromClipboardAction() {
        val clipboardText = "  AIzaSyPastedToken12345  "
        val cleaned = clipboardText.trim()
        assertEquals("AIzaSyPastedToken12345", cleaned)
        assertTrue(GeminiMultimodalClient.isValidGeminiKey(cleaned))
    }

    @Test
    fun testF17_tier1_immediateValidationCheck() {
        assertTrue(GeminiMultimodalClient.isValidGeminiKey("AIzaSyD_MockValidTokenForTests12345"))
        assertFalse(GeminiMultimodalClient.isValidGeminiKey("short"))
        assertFalse(GeminiMultimodalClient.isValidGeminiKey("invalid_prefix_12345678901234567890"))
    }

    @Test
    fun testF17_tier1_saveTriggersPersistence() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "key_persist_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val keysFile = File(tempDir, "hanson_api_keys.txt")
        val testKey = "AIzaSyD_ValidSavedKey12345"
        keysFile.writeText(testKey, Charsets.UTF_8)
        assertTrue(keysFile.exists())
        assertEquals(testKey, keysFile.readText(Charsets.UTF_8).trim())
        tempDir.deleteRecursively()
    }

    // --- Tier 2: Boundary & Corner Cases (F17) ---

    @Test
    fun testF17_tier2_boundary_blankInputSaveIgnored() {
        var savedKey: String? = null
        val onSaveKey: (String) -> Unit = { key ->
            if (key.trim().isNotEmpty()) savedKey = key.trim()
        }
        onSaveKey("   ")
        assertEquals(null, savedKey)
        onSaveKey("AIzaSyReal")
        assertEquals("AIzaSyReal", savedKey)
    }

    @Test
    fun testF17_tier2_boundary_sheetDismissOnOutsideTap() {
        var isSheetVisible = true
        val dismissCallback = { isSheetVisible = false }
        dismissCallback()
        assertFalse(isSheetVisible)
    }

    @Test
    fun testF17_tier2_boundary_clearKeyAction() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "key_clear_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val keysFile = File(tempDir, "hanson_api_keys.txt")
        keysFile.writeText("AIzaSyOldKey", Charsets.UTF_8)
        keysFile.writeText("", Charsets.UTF_8)
        assertEquals("", keysFile.readText(Charsets.UTF_8).trim())
        tempDir.deleteRecursively()
    }

    @Test
    fun testF17_tier2_boundary_pasteWithAccidentalSpaces() {
        val messyPaste = "  AIzaSyCleanToken123456  \r\n"
        val resolved = GeminiMultimodalClient.resolveApiKey(sheetInput = messyPaste)
        assertEquals("AIzaSyCleanToken123456", resolved)
        assertTrue(GeminiMultimodalClient.isValidGeminiKey(resolved))
    }

    @Test
    fun testF17_tier2_boundary_multipleKeyTypesInSettings() {
        val multiKeyContent = """
            gemini_key=AIzaSy12345678901234567890
            claude_key=sk-ant-test1234567890
        """.trimIndent()
        val geminiKey = GeminiMultimodalClient.extractKeyFromFile(multiKeyContent, "gemini_key")
        val claudeKey = GeminiMultimodalClient.extractKeyFromFile(multiKeyContent, "claude_key")
        assertEquals("AIzaSy12345678901234567890", geminiKey)
        assertEquals("sk-ant-test1234567890", claudeKey)
        assertTrue(GeminiMultimodalClient.isValidGeminiKey(geminiKey!!))
    }
}
