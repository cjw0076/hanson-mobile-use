package com.hanson.mobileuse

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.AgentMediaFileEngine
import com.hanson.mobileuse.agent.AgentToolExecutionHarness
import com.hanson.mobileuse.agent.ArtemisDeliberativeAligner
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.FinancialAndTakeoverGuard
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.HighStakesKind
import com.hanson.mobileuse.agent.HumanMemoryBubblesStore
import com.hanson.mobileuse.agent.HumanSummaryAndCardBuilder
import com.hanson.mobileuse.agent.IntentCategory
import com.hanson.mobileuse.agent.IntentDecomposerAndRouter
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.PermissionHubManager
import com.hanson.mobileuse.agent.PermissionHubState
import com.hanson.mobileuse.agent.PickleA2AScheduleNegotiator
import com.hanson.mobileuse.agent.ProactiveWatcherEngine
import com.hanson.mobileuse.agent.StructuredUiTemplateType
import com.hanson.mobileuse.agent.ToolSpecRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Comprehensive P0 + P1 Regression Suite (`ModernAiPipelineRegressionTest`):
 * Verifies every modernized contract across ToolSpecRegistry, GeminiMultimodalClient,
 * AgentToolExecutionHarness, HumanSummaryAndCardBuilder, IntentDecomposerAndRouter,
 * HumanFirstConvenienceEngine, LiveVoiceSessionManager, ProactiveWatcherEngine,
 * FinancialAndTakeoverGuard, PickleA2AScheduleNegotiator, and PermissionHubManager.
 */
class ModernAiPipelineRegressionTest {

    private lateinit var tempDir: File
    private lateinit var dummyContext: Context
    private lateinit var harness: AgentToolExecutionHarness

    @Before
    fun setUp() {
        GeminiMultimodalClient.Companion.MultiProviderApiKeySet.clearDeadKeys()
        GeminiMultimodalClient.Companion.MultiProviderApiKeySet.clearRateLimits()
        GeminiMultimodalClient.lastCloudApiErrorReason = null

        tempDir = Files.createTempDirectory("hanson_modern_ai_regression_").toFile()

        val prefsMap = mutableMapOf<String, Any>()
        val dummyPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? =
                (prefsMap[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                @Suppress("UNCHECKED_CAST")
                ((prefsMap[key] as? MutableSet<String>) ?: defValues)
            override fun getInt(key: String?, defValue: Int): Int = (prefsMap[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (prefsMap[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (prefsMap[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                (prefsMap[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(k: String?, v: String?): SharedPreferences.Editor {
                    if (k != null && v != null) prefsMap[k] = v
                    return this
                }
                override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor {
                    if (k != null && v != null) prefsMap[k] = v
                    return this
                }
                override fun putInt(k: String?, v: Int): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v
                    return this
                }
                override fun putLong(k: String?, v: Long): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v
                    return this
                }
                override fun putFloat(k: String?, v: Float): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v
                    return this
                }
                override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor {
                    if (k != null) prefsMap[k] = v
                    return this
                }
                override fun remove(k: String?): SharedPreferences.Editor {
                    prefsMap.remove(k)
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    prefsMap.clear()
                    return this
                }
                override fun commit(): Boolean = true
                override fun apply() {}
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.hanson.mobileuse"
            override fun getFilesDir(): File = tempDir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = dummyPrefs
        }

        harness = AgentToolExecutionHarness(dummyContext)
    }

    @After
    fun tearDown() {
        GeminiMultimodalClient.Companion.MultiProviderApiKeySet.clearDeadKeys()
        GeminiMultimodalClient.Companion.MultiProviderApiKeySet.clearRateLimits()
        GeminiMultimodalClient.lastCloudApiErrorReason = null
        tempDir.deleteRecursively()
    }

    private fun resolveRepoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile ?: cwd
    }

    private fun collectSchemaTypeValues(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key == "type" && value is String) {
                        out.add(value)
                    } else {
                        collectSchemaTypeValues(value, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectSchemaTypeValues(node.opt(i), out)
                }
            }
        }
    }

    private fun makeUiElement(
        index: Int,
        text: String,
        packageName: String = "com.example.shop",
        centerX: Int = 540,
        centerY: Int = 1200,
        enabled: Boolean = true,
        clickable: Boolean = true,
        editable: Boolean = false
    ): IndexedUiElement = IndexedUiElement(
        index = index,
        shortClass = if (editable) "EditText" else "Button",
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        packageName = packageName,
        text = text,
        contentDesc = text,
        resourceId = "$packageName:id/el_$index",
        left = (centerX - 80).coerceAtLeast(0),
        top = (centerY - 40).coerceAtLeast(0),
        right = centerX + 80,
        bottom = centerY + 40,
        centerX = centerX,
        centerY = centerY,
        normX = (centerX * 1000) / 1080,
        normY = (centerY * 1000) / 2400,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = editable,
        checkable = false,
        checked = false,
        focused = false,
        enabled = enabled,
        windowType = "APPLICATION",
        windowLayer = 0
    )

    // =========================================================================
    // 1. ToolSpecRegistry: Lowercase Standard Schemas, 45 Tools & Scoped Emitters
    // =========================================================================

    @Test
    fun testToolSpecRegistry_standardSchemaEmittersUseStrictlyLowercaseTypesAndKeepGeminiUppercase() {
        val allowedLowercaseTypes = setOf("object", "string", "integer", "number", "boolean", "array", "function")

        val emitters = mapOf(
            "OpenAI" to ToolSpecRegistry.toOpenAiToolsJson(),
            "Anthropic" to ToolSpecRegistry.toAnthropicToolsJson(),
            "MCP" to ToolSpecRegistry.toMcpToolsJson(),
            "GeminiInteractions" to ToolSpecRegistry.toGeminiInteractionsToolsJson()
        )

        for ((label, jsonArray) in emitters) {
            assertEquals("$label emitter must emit all 45 registered tools", 45, jsonArray.length())
            val types = mutableListOf<String>()
            collectSchemaTypeValues(jsonArray, types)
            assertTrue("$label schema must declare type fields", types.isNotEmpty())
            for (t in types) {
                assertEquals(
                    "$label schema MUST use strictly lowercase JSON Schema type (found '$t')",
                    t.lowercase(Locale.ROOT),
                    t
                )
                assertTrue(
                    "$label schema type '$t' must be a valid standard JSON Schema/tool type",
                    t in allowedLowercaseTypes
                )
            }
        }

        // Verify Gemini generateContent ProtoJSON schema intentionally retains uppercase types
        val geminiTypes = mutableListOf<String>()
        collectSchemaTypeValues(ToolSpecRegistry.toGeminiToolsJson(), geminiTypes)
        assertTrue("Gemini ProtoJSON schema should include uppercase OBJECT", "OBJECT" in geminiTypes)
        assertTrue("Gemini ProtoJSON schema should include uppercase STRING", "STRING" in geminiTypes)
    }

    @Test
    fun testToolSpecRegistry_registers45ToolsIncludingObserveScreenSetTimerLifestyleRoutineAndScoping() {
        assertEquals(45, ToolSpecRegistry.allSpecs.size)
        assertNotNull("set_timer must be registered", ToolSpecRegistry.find("set_timer"))
        assertNotNull("execute_lifestyle_routine must be registered", ToolSpecRegistry.find("execute_lifestyle_routine"))
        assertNotNull("observe_screen must be registered", ToolSpecRegistry.find("observe_screen"))

        // Interactions declarations must preserve spoken_reply parity with Gemini generateContent declarations
        val interactions = ToolSpecRegistry.toGeminiInteractionsToolsJson()
        for (i in 0 until interactions.length()) {
            val decl = interactions.getJSONObject(i)
            val props = decl.getJSONObject("parameters").getJSONObject("properties")
            assertTrue(
                "Interactions tool '${decl.optString("name")}' must expose spoken_reply property",
                props.has("spoken_reply")
            )
            assertEquals("string", props.getJSONObject("spoken_reply").optString("type"))
        }

        // Scoped text-first selection omits GUI + workspace tools while keeping observe_screen
        val textFirstSpecs = ToolSpecRegistry.selectScopedSpecs(
            includeGuiTools = false,
            includeWorkspaceTools = false
        )
        val textFirstNames = textFirstSpecs.map { it.name }.toSet()
        assertTrue(
            "observe_screen must stay enabled even when includeGuiTools=false",
            "observe_screen" in textFirstNames
        )
        for (guiTool in ToolSpecRegistry.GUI_TOOL_NAMES) {
            assertFalse("GUI tool '$guiTool' must be omitted in text-first scope", guiTool in textFirstNames)
        }
        for (wsTool in ToolSpecRegistry.WORKSPACE_TOOL_NAMES) {
            assertFalse("Workspace tool '$wsTool' must be omitted in text-first scope", wsTool in textFirstNames)
        }
    }

    // =========================================================================
    // 2. GeminiMultimodalClient: 429 vs 402/401, Live Model Names, Key Parser,
    //    Thought Filtering & Honest Offline Fallback
    // =========================================================================

    @Test
    fun testGeminiClient_429ResourceExhaustedRateLimitsModelWithoutKillingKeyWhile402And401KillKey() {
        val client = GeminiMultimodalClient()
        val method = GeminiMultimodalClient::class.java.getDeclaredMethod(
            "handleCloudProviderFailure",
            String::class.java,
            String::class.java,
            String::class.java,
            Throwable::class.java
        )
        method.isAccessible = true

        val key429 = "AIzaSyRateLimitTestKey1234567890"
        val diag429 = method.invoke(
            client,
            "Gemini",
            "gemini-2.5-flash",
            key429,
            IllegalStateException("Gemini HTTP 429: RESOURCE_EXHAUSTED quota exceeded")
        ) as String

        assertTrue("429 diagnostic must record HTTP 429", diag429.contains("429"))
        assertFalse(
            "HTTP 429 RESOURCE_EXHAUSTED MUST NOT poison the API key as temporarily dead",
            GeminiMultimodalClient.Companion.MultiProviderApiKeySet.isKeyTemporarilyDead(key429)
        )
        assertTrue(
            "HTTP 429 RESOURCE_EXHAUSTED must apply per-(key, model) cooldown",
            GeminiMultimodalClient.Companion.MultiProviderApiKeySet.isModelRateLimited(key429, "gemini-2.5-flash")
        )
        assertFalse(
            "Per-(key, model) cooldown must NOT block a sibling fallback model on the same key",
            GeminiMultimodalClient.Companion.MultiProviderApiKeySet.isModelRateLimited(key429, "gemini-2.5-flash-lite")
        )
        assertTrue(
            "lastCloudApiErrorReason must be populated on 429",
            GeminiMultimodalClient.lastCloudApiErrorReason.orEmpty().contains("429")
        )

        // Contrast: HTTP 402 and HTTP 401 MUST mark the key temporarily dead
        val key402 = "sk-or-depleted-credits-test-key"
        method.invoke(
            client,
            "OpenRouter",
            "openai/gpt-4.1",
            key402,
            IllegalStateException("OpenRouter HTTP 402: Insufficient credits")
        )
        assertTrue(
            "HTTP 402 must mark key temporarily dead",
            GeminiMultimodalClient.Companion.MultiProviderApiKeySet.isKeyTemporarilyDead(key402)
        )

        val key401 = "AIzaSyInvalidKeyTest1234567890"
        method.invoke(
            client,
            "Gemini",
            "gemini-2.5-flash",
            key401,
            IllegalStateException("Gemini HTTP 401: API_KEY_INVALID")
        )
        assertTrue(
            "HTTP 401 / API_KEY_INVALID must mark key temporarily dead",
            GeminiMultimodalClient.Companion.MultiProviderApiKeySet.isKeyTemporarilyDead(key401)
        )
    }

    @Test
    fun testGeminiClient_normalize2026ModelName_preservesLive25FlashAndMigratesRetiredModels() {
        assertEquals("gemini-3.8-flash", GeminiMultimodalClient.DEFAULT_MODEL)
        assertEquals("gemini-2.5-flash", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-flash"))
        assertEquals("gemini-2.5-flash-lite", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-flash-lite"))
        assertEquals("gemini-3.8-flash", GeminiMultimodalClient.normalize2026ModelName("gemini-2.0-flash"))
        assertEquals("gemini-3.1-pro-preview", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-pro"))

        val pickerIds = GeminiMultimodalClient.VLM_PICKER_MODELS.map { it.first }
        assertTrue("VLM_PICKER_MODELS must include live gemini-3.8-flash", "gemini-3.8-flash" in pickerIds)
        assertTrue("VLM_PICKER_MODELS must include live gemini-2.5-flash", "gemini-2.5-flash" in pickerIds)
        assertFalse("VLM_PICKER_MODELS must exclude retired gemini-2.0-flash", "gemini-2.0-flash" in pickerIds)
        assertFalse("VLM_PICKER_MODELS must exclude retired gemini-2.5-pro", "gemini-2.5-pro" in pickerIds)
        assertFalse("VLM_PICKER_MODELS must exclude unusable antigravity-preview-09-2026", "antigravity-preview-09-2026" in pickerIds)
    }

    @Test
    fun testMultiProviderApiKeySet_parseIgnoresCommentsEnvPrefixesAndBearerNoise() {
        val sampleGemini = "AIza" + "SyLiveGeminiKey1234567890"
        val sampleAqKey = "AQ." + "DemoStudioKeyFormat1234567890abcdef"
        val sampleOpenAi = "sk-" + "live-openai-key-1234567890"
        val sampleAnthropic = "sk-" + "ant-live-claude-key-1234567890"
        val sampleOpenRouter = "sk-" + "or-live-router-key-1234567890"
        val raw = """
            # User API keys configuration
            // Inline comment line
            Authorization: Bearer not-a-real-key-token
            export GEMINI_API_KEY="$sampleGemini" # inline comment
            OPENAI_API_KEY=$sampleOpenAi
            ANTHROPIC_API_KEY=$sampleAnthropic
            OPENROUTER_API_KEY=$sampleOpenRouter
        """.trimIndent()

        val parsed = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(raw)
        assertEquals(sampleGemini, parsed.geminiKey)
        assertEquals(sampleOpenAi, parsed.openAiKey)
        assertEquals(sampleAnthropic, parsed.anthropicKey)
        assertEquals(sampleOpenRouter, parsed.openRouterKey)

        // New Google AI Studio AQ. key format must also be recognized and validated
        val parsedAq = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(sampleAqKey)
        assertEquals(sampleAqKey, parsedAq.geminiKey)
        assertTrue("AQ. Google AI Studio keys must be valid", GeminiMultimodalClient.isValidGeminiKey(sampleAqKey))

        // OpenAI /v1/responses web_search_preview parser check
        val responsesJson = JSONObject().put(
            "output",
            JSONArray().put(
                JSONObject()
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", "SOXS 최근 종가는 83.16달러입니다.")
                                .put(
                                    "annotations",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "url_citation")
                                            .put("title", "Market Quote")
                                            .put("url", "https://finance.yahoo.com/quote/SOXS")
                                    )
                                )
                        )
                    )
            )
        ).toString()
        val parsedWeb = GeminiMultimodalClient.parseOpenAiResponsesWebSearchBody(responsesJson, "gpt-4.1-mini")
        assertTrue(parsedWeb.succeeded)
        assertTrue(parsedWeb.answerKo.contains("83.16"))
        assertEquals(1, parsedWeb.citations.size)
        assertEquals("https://finance.yahoo.com/quote/SOXS", parsedWeb.citations[0].second)

        // Noise-only input MUST never pollute geminiKey
        val noiseOnly = """
            # Just comments
            Authorization: Bearer arbitrary_word
            OPENAI_API_KEY=
            x-goog-api-key:
        """.trimIndent()
        val parsedNoise = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(noiseOnly)
        assertNull("Noise/comments/Bearer headers must NEVER be assigned to geminiKey", parsedNoise.geminiKey)
        assertFalse("Noise-only input must report hasAnyCloudKey == false", parsedNoise.hasAnyCloudKey)
    }

    @Test
    fun testDeserializeCardResponse_filtersOutThoughtTruePartsAndHandlesJsonNull() {
        val geminiJson = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("thought", true).put("text", "HIDDEN_THOUGHT_CHAIN"))
                            .put(JSONObject().put("text", JSONObject.NULL))
                            .put(JSONObject().put("thought", false).put("text", "SOXS 현재가 요약입니다."))
                    )
                )
            )
        )

        val decoded = GeminiMultimodalClient.deserializeCardResponse(geminiJson.toString())
        assertEquals("SOXS 현재가 요약입니다.", decoded)
        assertFalse("thought==true content must never leak into user output", decoded.contains("HIDDEN_THOUGHT_CHAIN"))
        assertFalse("JSON null text must never render literal 'null'", decoded.contains("null"))

        val mediaEngineDecoded = AgentMediaFileEngine.extractNonThoughtTextFromGeminiResponse(geminiJson)
        assertEquals("SOXS 현재가 요약입니다.", mediaEngineDecoded)
    }

    @Test
    fun testInferLocalOnDeviceAction_soxsPriceQueryReturnsHonestAskUserWithoutReplyHijackOrDebugLeak() {
        val client = GeminiMultimodalClient()
        val snap = AtomicSnapshot(
            activePackage = "com.hanson.mobileuse.debug",
            activeWindowTitle = "Hanson AI"
        )

        val decision = client.inferLocalOnDeviceAction(
            goal = "soxs 가격 알려 줘",
            iteration = 1,
            snapshot = snap,
            historySteps = emptyList(),
            previousPlan = emptyList()
        )

        assertEquals("Offline fallback must honestly ask_user for AI setup", "ask_user", decision.toolName)
        assertFalse(
            "Offline fallback must NEVER hijack price query with '어떤 내용으로 답장을 보낼까요?'",
            decision.spokenReply.contains("어떤 내용으로 답장을 보낼까요?")
        )
        assertFalse(
            "Offline fallback must NEVER emit duplicated '화면 화면'",
            decision.spokenReply.contains("화면 화면")
        )
        assertFalse(
            "Offline fallback must NEVER leak '.debug' package suffix into Korean prose",
            decision.spokenReply.contains(".debug")
        )
    }

    // =========================================================================
    // 3. AgentToolExecutionHarness: resolveTargetPixel Priority & Normalization
    // =========================================================================

    @Test
    fun testResolveTargetPixel_priority1ElementIndexAnd0To1000NormalizationContract() {
        val resolveMethod = AgentToolExecutionHarness::class.java.getDeclaredMethod(
            "resolveTargetPixel",
            JSONObject::class.java,
            AtomicSnapshot::class.java
        )
        resolveMethod.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        fun resolve(args: JSONObject, snap: AtomicSnapshot): Pair<Int, Int>? =
            resolveMethod.invoke(harness, args, snap) as Pair<Int, Int>?

        val snap = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.example.app",
            indexedElements = listOf(
                makeUiElement(index = 3, text = "확인 버튼", centerX = 220, centerY = 640),
                makeUiElement(index = 5, text = "검색", centerX = 900, centerY = 120)
            )
        )

        // Priority 1: element_index takes precedence over norm_x/norm_y, x/y, and target_description
        val byIndex = resolve(
            JSONObject()
                .put("element_index", 3)
                .put("norm_x", 800)
                .put("norm_y", 800)
                .put("x", 100)
                .put("y", 100)
                .put("target_description", "검색"),
            snap
        )
        assertEquals(220 to 640, byIndex)

        // Priority 2: explicit norm_x / norm_y (0..1000) -> pixel
        val byNorm = resolve(
            JSONObject().put("norm_x", 250).put("norm_y", 750),
            snap
        )
        assertEquals(270 to 1800, byNorm)

        // Priority 3a: x / y both in 0..1000 are treated as normalized 0..1000 coordinates
        val byNormalizedXY = resolve(
            JSONObject().put("x", 500).put("y", 500),
            snap
        )
        assertEquals(540 to 1200, byNormalizedXY)

        val byNormalizedEdgeXY = resolve(
            JSONObject().put("x", 450).put("y", 950),
            snap
        )
        assertEquals(486 to 2280, byNormalizedEdgeXY)

        // Priority 3b: x / y with either coordinate > 1000 are treated as raw pixels
        val byRawPixelXY = resolve(
            JSONObject().put("x", 450).put("y", 1950),
            snap
        )
        assertEquals(450 to 1950, byRawPixelXY)
    }

    // =========================================================================
    // 4. AgentRunModels: HumanSummaryAndCardBuilder Verified HTTPS Citations
    // =========================================================================

    @Test
    fun testHumanSummaryAndCardBuilder_producesVerifiedHttpsCitationsAndNeverEmitsAllRegionsOrEmptyUrls() {
        val rawGrounded = """
            SOXS는 ICE Semiconductor Index 일간 수익률의 -3배를 추종하는 인버스 ETF입니다.
            All Regions

            출처:
            - [SOXS Quote & Chart](https://finance.yahoo.com/quote/SOXS)
            - Direxion SOXS ETF | https://www.direxion.com/product/daily-semiconductor-bull-and-bear-3x-etfs
            - [Malicious JS](javascript:alert(1))
            - [Insecure HTTP](http://example.com/soxs)
            - [Relative Link](/quote/SOXS)
        """.trimIndent()

        val extracted = HumanSummaryAndCardBuilder.extractVerifiedHttpsCitations(rawGrounded)
        assertEquals("Only the 2 verified HTTPS citations must be extracted", 2, extracted.size)
        for (item in extracted) {
            assertTrue("Every actionUrlOrCmd must be non-empty", item.actionUrlOrCmd.isNotBlank())
            assertTrue(
                "Every actionUrlOrCmd must start with https:// (was '${item.actionUrlOrCmd}')",
                item.actionUrlOrCmd.startsWith("https://")
            )
            assertFalse("Must NEVER emit DuckDuckGo 'All Regions' noise", item.title.contains("All Regions"))
            assertFalse("Must NEVER emit DuckDuckGo 'All Regions' noise", item.subtitle.contains("All Regions"))
        }

        val enriched = HumanSummaryAndCardBuilder.enrichMessageForHumanDisplay(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = rawGrounded,
                toolBadge = "WEB-GROUNDING"
            )
        )
        assertNotNull("Grounded web response must produce a StructuredUiCard", enriched.structuredCard)
        assertEquals(StructuredUiTemplateType.CURATED_LIST, enriched.structuredCard?.templateType)
        val cardItems = enriched.structuredCard?.items.orEmpty()
        assertEquals(2, cardItems.size)
        assertTrue(cardItems.all { it.actionUrlOrCmd.startsWith("https://") })
        assertTrue(cardItems.none { it.title.contains("All Regions", ignoreCase = true) })
        assertTrue(enriched.detailPayload?.sourceUrl?.startsWith("https://") == true)
    }

    // =========================================================================
    // 5. IntentDecomposerAndRouter: Zero Hijacking & Explicit Numeric Timer
    // =========================================================================

    @Test
    fun testIntentDecomposer_neverHijacksKnowledgeOrAmbiguousQueriesAndRoutesExplicitNumericTimer() {
        val mustNotHijack = listOf(
            "soxs 가격 알려 줘",
            "SOXS 내려갔어?",
            "알람 꺼줘",
            "타이머 멈춰",
            "라이트 뭐야"
        )
        for (prompt in mustNotHijack) {
            val plan = IntentDecomposerAndRouter.decompose(prompt)
            assertEquals(
                "'$prompt' must flow to MULTI_STEP_GUI_OR_CHAT (AI)",
                IntentCategory.MULTI_STEP_GUI_OR_CHAT,
                plan.primaryCategory
            )
            assertNull("'$prompt' must NOT produce a regex fastDecision", plan.fastDecision)
        }

        val timerPlan = IntentDecomposerAndRouter.decompose("3분 타이머 맞춰줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, timerPlan.primaryCategory)
        assertNotNull("Explicit numeric timer must produce fastDecision", timerPlan.fastDecision)
        assertEquals("set_timer", timerPlan.fastDecision?.toolName)
        assertEquals(180, timerPlan.fastDecision?.toolArgs?.optInt("seconds"))
    }

    // =========================================================================
    // 6. HumanFirstConvenienceEngine: Clean Memory Bubbles & Advisory Aligner
    // =========================================================================

    @Test
    fun testMemoryBubblesStore_seedDefaultsIfEmptyIsNoOpAndNeverInjectsReplyPrompt() {
        val storeFile = File(tempDir, "empty_memory_bubbles.json")
        val store = HumanMemoryBubblesStore(storeFile)
        store.seedDefaultsIfEmpty()

        assertTrue("seedDefaultsIfEmpty() must leave bubbles empty", store.bubbles.value.isEmpty())
        val sysPrompt = store.formatBubblesForSystemPrompt()
        val contextBlock = store.toPromptContextBlock()
        assertTrue("Empty memory bubbles must yield empty system prompt", sysPrompt.isEmpty())
        assertFalse("System prompt must NEVER auto-inject '답장'", sysPrompt.contains("답장"))
        assertFalse("Prompt context block must NEVER auto-inject '답장'", contextBlock.contains("답장"))
    }

    @Test
    fun testArtemisDeliberativeAligner_neverMutatesVlmCoordinatesOrElementIndex() {
        val snapshot = AtomicSnapshot(
            activePackage = "com.kakao.talk",
            indexedElements = listOf(
                makeUiElement(
                    index = 0,
                    text = "Hanson HUD",
                    packageName = "com.hanson.mobileuse",
                    centerX = 120,
                    centerY = 120,
                    enabled = true
                ),
                makeUiElement(
                    index = 1,
                    text = "전송",
                    packageName = "com.kakao.talk",
                    centerX = 950,
                    centerY = 2100,
                    enabled = false
                )
            )
        )

        val vlmArgs = JSONObject()
            .put("element_index", 0)
            .put("norm_x", 111)
            .put("norm_y", 222)
            .put("x", 120)
            .put("y", 120)
            .put("target_description", "전송")

        val result = ArtemisDeliberativeAligner.alignBeforeExecution(
            toolName = "click",
            args = vlmArgs,
            thoughtOrReply = "전송 버튼을 누릅니다",
            snapshot = snapshot,
            iteration = 1
        )

        assertFalse("ArtemisDeliberativeAligner must NEVER mutate VLM tool calls", result.wasCorrected)
        assertEquals("click", result.toolName)
        assertEquals(0, result.alignedArgs.optInt("element_index", -1))
        assertEquals(111, result.alignedArgs.optInt("norm_x", -1))
        assertEquals(222, result.alignedArgs.optInt("norm_y", -1))
        assertEquals(120, result.alignedArgs.optInt("x", -1))
        assertEquals(120, result.alignedArgs.optInt("y", -1))
        assertTrue(
            "Advisory warning should still inform caller when targeting Hanson own UI",
            result.advisoryWarningKo.isNotBlank()
        )
    }

    // =========================================================================
    // 7. LiveVoiceSessionManager: v1beta BidiGenerateContent, Header Auth, AEC
    // =========================================================================

    @Test
    fun testLiveVoiceSessionManager_v1betaBidiEndpointHeaderAuthHandshakeAndSourcePrimitives() {
        val endpoint = LiveVoiceSessionManager.GEMINI_LIVE_WEBSOCKET_ENDPOINT
        assertTrue("Live WebSocket endpoint must target v1beta", endpoint.contains("v1beta"))
        assertTrue("Live WebSocket endpoint must target BidiGenerateContent", endpoint.contains("BidiGenerateContent"))
        assertFalse("Live WebSocket endpoint must NOT embed query API keys", endpoint.contains("key="))

        val req = LiveVoiceSessionManager.buildLiveWebSocketRequest("AIzaSyLiveVoiceHeaderAuthTest123")
        assertEquals("AIzaSyLiveVoiceHeaderAuthTest123", req.header("x-goog-api-key"))
        assertFalse("Live WebSocket URL must NOT carry ?key= query param", req.url.toString().contains("key="))

        val handshake = LiveVoiceSessionManager.buildLiveSetupHandshake()
        val setup = handshake.getJSONObject("setup")
        assertTrue("Setup handshake must include systemInstruction", setup.has("systemInstruction"))
        assertTrue("Setup handshake must include inputAudioTranscription", setup.has("inputAudioTranscription"))
        assertTrue("Setup handshake must include outputAudioTranscription", setup.has("outputAudioTranscription"))

        val sourceFile = File(resolveRepoRoot(), "app/src/main/java/com/hanson/mobileuse/agent/LiveVoiceSessionManager.kt")
        assertTrue("LiveVoiceSessionManager.kt must exist", sourceFile.isFile)
        val source = sourceFile.readText()
        assertTrue("Source must reference v1beta", source.contains("v1beta"))
        assertTrue("Source must reference BidiGenerateContent", source.contains("BidiGenerateContent"))
        assertTrue("Source must enforce RECORD_AUDIO permission", source.contains("RECORD_AUDIO"))
        assertTrue("Source must attach hardware AcousticEchoCanceler", source.contains("AcousticEchoCanceler"))
    }

    // =========================================================================
    // 8. Additional P0/P1 Safety & Honesty Contracts
    // =========================================================================

    @Test
    fun testProactiveWatcherEngine_rejectsBlanketWildcardKeywords() {
        val forbidden = listOf("", "   ", "전체", "모두", "all", "ALL", "any", "*")
        for (kw in forbidden) {
            assertTrue("'$kw' must be rejected as a forbidden wildcard keyword", ProactiveWatcherEngine.isForbiddenWildcardKeyword(kw))
            assertNull("sanitizeTriggerKeyword('$kw') must return null", ProactiveWatcherEngine.sanitizeTriggerKeyword(kw))
        }
        assertEquals("잔여좌석", ProactiveWatcherEngine.sanitizeTriggerKeyword("  잔여좌석  "))

        val watcherEngine = ProactiveWatcherEngine(File(tempDir, "watchers_regression.json"))
        try {
            watcherEngine.addRule(
                title = "Wildcard Attempt",
                targetAppOrUrl = "com.kakao.talk",
                matchKeywords = listOf("전체", "*")
            )
            fail("ProactiveWatcherEngine.addRule must reject rules with only wildcard keywords")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun testFinancialAndTakeoverGuard_inputTextPressEnterDefaultTrueAndSubmitEnterFallback() {
        val checkoutSnapshot = AtomicSnapshot(
            activePackage = "com.example.pay",
            activeWindowTitle = "결제하기",
            indexedElements = listOf(
                makeUiElement(index = 0, text = "총 결제금액 50,000원", clickable = false),
                makeUiElement(index = 1, text = "결제하기", editable = true)
            )
        )

        // Default press_enter omitted -> defaults to true (matching AgentToolExecutionHarness.executeInputText)
        val defaultEnter = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "input_text",
            args = JSONObject().put("element_index", 1).put("text", "확인"),
            snapshot = checkoutSnapshot
        )
        assertEquals(HighStakesKind.FINANCIAL_COMMIT, defaultEnter.kind)

        // Explicit press_enter = false -> non-commit typing
        val noEnter = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "input_text",
            args = JSONObject().put("element_index", 1).put("text", "메모").put("press_enter", false),
            snapshot = checkoutSnapshot
        )
        assertEquals(HighStakesKind.NONE, noEnter.kind)

        // Legacy submit_enter = false fallback -> non-commit typing
        val legacyNoEnter = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = "input_text",
            args = JSONObject().put("element_index", 1).put("text", "메모").put("submit_enter", false),
            snapshot = checkoutSnapshot
        )
        assertEquals(HighStakesKind.NONE, legacyNoEnter.kind)
    }

    @Test
    fun testPickleA2AScheduleNegotiator_labelsOfflineLocalDraftWhenNoRemoteEndpointConfigured() {
        val outcome = PickleA2AScheduleNegotiator.negotiate(
            peerName = "김대리",
            durationMinutes = 60,
            myBusyCsv = "12:00-13:00",
            peerFreeCsv = "12:00-15:00"
        )
        assertTrue("Local negotiation without remote endpoint must be marked isLocalDraft=true", outcome.isLocalDraft)
        assertTrue("remoteEndpointUrl must be empty for offline draft", outcome.remoteEndpointUrl.isEmpty())
    }

    @Test
    fun testPermissionHub_strictAccessibilityReadinessNeverFakesConnectedFromPercent() {
        assertNull("Precondition: live accessibility service is null in unit tests", HansonAccessibilityService.instance)
        val hub = PermissionHubManager(dummyContext)
        assertFalse(
            "isAccessibilityReady() must return false when HansonAccessibilityService.instance is null",
            hub.isAccessibilityReady()
        )
        val highPercentDisconnected = PermissionHubState(
            grantedCount = 9,
            totalCount = 10,
            isAccessibilityReady = false,
            isOverlayReady = true
        )
        assertEquals(90, highPercentDisconnected.readinessPercent)
        assertFalse(
            "isGuiAutomationReady must NEVER fake readiness from readinessPercent >= 80",
            highPercentDisconnected.isGuiAutomationReady
        )
    }
}
