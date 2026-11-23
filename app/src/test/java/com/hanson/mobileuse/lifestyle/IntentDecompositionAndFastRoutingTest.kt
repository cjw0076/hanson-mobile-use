package com.hanson.mobileuse.lifestyle

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.accessibility.SetOfMarksAnnotator
import com.hanson.mobileuse.agent.AgentToolExecutionHarness
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.IntentCategory
import com.hanson.mobileuse.agent.IntentDecomposerAndRouter
import com.hanson.mobileuse.agent.LifestyleTaskModality
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.SpatialHint
import com.hanson.mobileuse.agent.inferModalityFromPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the modernized, strictly anchored `IntentDecomposerAndRouter` (B4 Deconstruction):
 * - Single-word external-app navigation (`"뒤로"`, `"홈"` ONLY when `activePackage` is non-blank and not Hanson).
 * - Strictly anchored explicit numeric alarm/timer creation (no silent 7 AM or 3-minute defaults).
 * - Zero substring hijacking for ambiguous/general prompts (`"soxs 가격 알려 줘"`, `"SOXS 내려갔어?"`,
 *   `"알람 꺼줘"`, `"타이머 멈춰"`, `"라이트 뭐야"`, `"한칸 내려줘"`, `"영상을 틀어 줘"`, etc.) so the real LLM/VLM handles them.
 * - Self-blindness shield against Hanson's own chat UI and Edge panel.
 */
class IntentDecompositionAndFastRoutingTest {

    private fun uiElement(
        index: Int,
        shortClass: String,
        text: String,
        contentDesc: String,
        resourceId: String,
        clickable: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        centerX: Int,
        centerY: Int,
        normX: Int,
        normY: Int,
        packageName: String = "com.google.android.youtube"
    ): IndexedUiElement = IndexedUiElement(
        index = index,
        shortClass = shortClass,
        className = "android.view.$shortClass",
        packageName = packageName,
        text = text,
        contentDesc = contentDesc,
        resourceId = resourceId,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        centerX = centerX,
        centerY = centerY,
        normX = normX,
        normY = normY,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = false,
        checkable = false,
        checked = false,
        focused = false,
        enabled = true,
        windowType = "APPLICATION",
        windowLayer = 0
    )

    private fun sampleYouTubeSnapshot(): AtomicSnapshot {
        return AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.google.android.youtube",
            activeWindowTitle = "YouTube",
            indexedElements = listOf(
                uiElement(
                    index = 1,
                    shortClass = "ImageView",
                    text = "",
                    contentDesc = "검색",
                    resourceId = "com.google.android.youtube:id/menu_item_1",
                    clickable = true,
                    left = 900,
                    top = 80,
                    right = 1020,
                    bottom = 180,
                    centerX = 960,
                    centerY = 130,
                    normX = 888,
                    normY = 55
                ),
                uiElement(
                    index = 2,
                    shortClass = "ViewGroup",
                    text = "",
                    contentDesc = "내 잘못 VS 도둑 잘못 [기시니 스릴러툰] - 6분 54초 - 동영상 재생",
                    resourceId = "",
                    clickable = true,
                    left = 0,
                    top = 340,
                    right = 1080,
                    bottom = 980,
                    centerX = 540,
                    centerY = 660,
                    normX = 500,
                    normY = 282
                ),
                uiElement(
                    index = 3,
                    shortClass = "TextView",
                    text = "돈이 최고인 이유.jpg",
                    contentDesc = "",
                    resourceId = "",
                    clickable = true,
                    left = 40,
                    top = 1280,
                    right = 1040,
                    bottom = 1720,
                    centerX = 540,
                    centerY = 1500,
                    normX = 500,
                    normY = 641
                )
            )
        )
    }

    @Test
    fun testSingleWordExternalNav_triggersOnlyForExternalAppAndNeverForHansonOrNull() {
        val externalSnap = sampleYouTubeSnapshot()
        assertTrue(IntentDecomposerAndRouter.isExternalAppSnapshot(externalSnap))

        val backIntent = IntentDecomposerAndRouter.decompose("뒤로", externalSnap)
        assertEquals(IntentCategory.REFLEX_MICRO_CONTROL, backIntent.primaryCategory)
        assertTrue(backIntent.autoCompleteSingleTurn)
        assertEquals("press_key", backIntent.fastDecision?.toolName)
        assertEquals("BACK", backIntent.fastDecision?.toolArgs?.getString("key"))

        val homeIntent = IntentDecomposerAndRouter.decompose("홈", externalSnap)
        assertEquals(IntentCategory.REFLEX_MICRO_CONTROL, homeIntent.primaryCategory)
        assertTrue(homeIntent.autoCompleteSingleTurn)
        assertEquals("press_key", homeIntent.fastDecision?.toolName)
        assertEquals("HOME", homeIntent.fastDecision?.toolArgs?.getString("key"))

        // When snapshot is null or Hanson's own UI, even single-word "뒤로"/"홈" must NOT hijack
        val nullSnapBack = IntentDecomposerAndRouter.decompose("뒤로", null)
        assertEquals(IntentCategory.MULTI_STEP_GUI_OR_CHAT, nullSnapBack.primaryCategory)
        assertNull(nullSnapBack.fastDecision)

        val hansonSnap = AtomicSnapshot(activePackage = "com.hanson.mobileuse.debug")
        assertFalse(IntentDecomposerAndRouter.isExternalAppSnapshot(hansonSnap))
        val ownSnapHome = IntentDecomposerAndRouter.decompose("홈", hansonSnap)
        assertEquals(IntentCategory.MULTI_STEP_GUI_OR_CHAT, ownSnapHome.primaryCategory)
        assertNull(ownSnapHome.fastDecision)
    }

    @Test
    fun testAmbiguousAndConversationalPrompts_delegateToRealAiWithoutHijacking() {
        val snap = sampleYouTubeSnapshot()
        val history = listOf(
            LiveChatMessage(sender = ChatSender.USER, text = "유튜브에서 영상 찾아줘")
        )

        val mustForwardToAi = listOf(
            "soxs 가격 알려 줘",
            "SOXS 내려갔어?",
            "알람 꺼줘",
            "알람 맞춰줘",
            "타이머 멈춰",
            "타이머 맞춰줘",
            "라이트 뭐야",
            "한칸 내려줘",
            "밑으로 내려 봐",
            "영상을 틀어 줘",
            "아니 클릭해서 틀어 주면 되잖아",
            "잘 준비 해줘",
            "집중 모드 켜줘",
            "손전등 켜줘",
            "진동 모드로 바꿔줘",
            "홈 화면으로 나가 봐",
            "배달의 민족 앱 켜서 족발 검색해 봐"
        )

        for (prompt in mustForwardToAi) {
            val intent = IntentDecomposerAndRouter.decompose(prompt, snap, history, isMainActivityInForeground = false)
            assertEquals(
                "Prompt '$prompt' must forward to MULTI_STEP_GUI_OR_CHAT, not regex hijack",
                IntentCategory.MULTI_STEP_GUI_OR_CHAT,
                intent.primaryCategory
            )
            assertNull("Prompt '$prompt' must have null fastDecision", intent.fastDecision)
        }
    }

    @Test
    fun testSafeCompatibilityStubs_neverFabricateFuzzyClicksOrHijackGenerativeFlags() {
        val snap = sampleYouTubeSnapshot()
        assertEquals(SpatialHint.NONE, IntentDecomposerAndRouter.extractSpatialHint("저 위에 첫 번째"))
        assertNull(IntentDecomposerAndRouter.findBestScreenElement(snap, "검색"))
        assertNull(IntentDecomposerAndRouter.extractSafeSearchQueryIfExplicit("아니 클릭해서 틀어 주면 되잖아"))
        assertEquals(null to null, IntentDecomposerAndRouter.extractCleanAppAndSearchQuery("배달의 민족 앱 켜서 족발 검색해 봐"))
        assertFalse(IntentDecomposerAndRouter.isExplicitGenerativeOrCodeRequest("영상을 틀어 줘", "영상을 틀어 줘", snap, false))
        assertFalse(IntentDecomposerAndRouter.isExplicitGenerativeOrCodeRequest("코드로 주간 매출 차트 그려줘"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION, inferModalityFromPrompt("영상을 틀어 줘"))
        assertEquals(LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA, inferModalityFromPrompt("노을 풍경 영상 모션 생성해줘"))
    }

    @Test
    fun testYouTubeQuotedSearchQueryAndExactAppLookup_workWithoutSubstringHijacking() {
        assertEquals(
            "아이유 밤편지",
            IntentDecomposerAndRouter.cleanYouTubeSearchQuery("유튜브 '아이유 밤편지' 영상 틀어줘")
        )
        assertEquals(
            "침착맨",
            IntentDecomposerAndRouter.cleanYouTubeSearchQuery("유튜브에서 '침착맨' 영상 틀어 줘")
        )
        assertEquals(
            "로파이 코딩 음악",
            IntentDecomposerAndRouter.cleanYouTubeSearchQuery("유튜브 로파이 코딩 음악 재생해 줘")
        )

        // Exact app name matches cleanly
        assertEquals("배달의민족" to "com.sampleapp", IntentDecomposerAndRouter.resolveTargetAppFromPrompt("배달의민족"))
        assertEquals("배달의민족" to "com.sampleapp", IntentDecomposerAndRouter.resolveTargetAppFromPrompt("배민 앱"))
        assertEquals("유튜브" to "com.google.android.youtube", IntentDecomposerAndRouter.resolveTargetAppFromPrompt("유튜브"))

        // Full sentences mentioning an app name must NOT be hijacked by substring match
        assertNull(IntentDecomposerAndRouter.resolveTargetAppFromPrompt("챗지피티 요금제 알려줘"))
        assertNull(IntentDecomposerAndRouter.resolveTargetAppFromPrompt("유튜브에서 로파이 틀어줘"))
    }

    @Test
    fun testEverydayOsAction_alarm9Am_neverClicksHansonOwnChatBubble() {
        val hansonSelfSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.hanson.mobileuse.debug",
            activeWindowTitle = "Hanson",
            indexedElements = listOf(
                uiElement(
                    index = 1,
                    shortClass = "TextView",
                    text = "9시 알람 맞춰 줘",
                    contentDesc = "",
                    resourceId = "",
                    clickable = true,
                    left = 240,
                    top = 900,
                    right = 1020,
                    bottom = 1010,
                    centerX = 630,
                    centerY = 955,
                    normX = 583,
                    normY = 408,
                    packageName = "com.hanson.mobileuse.debug"
                )
            )
        )

        assertTrue(IntentDecomposerAndRouter.isHansonOwnElement(hansonSelfSnapshot.indexedElements.first()))
        assertFalse(IntentDecomposerAndRouter.isExternalAppSnapshot(hansonSelfSnapshot))
        assertNull(AgentToolExecutionHarness.findBestMatchingElement(hansonSelfSnapshot, "9시 알람 맞춰 줘"))

        val alarmIntent = IntentDecomposerAndRouter.decompose(
            rawPrompt = "9시 알람 맞춰 줘",
            snapshot = hansonSelfSnapshot,
            isMainActivityInForeground = true
        )
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, alarmIntent.primaryCategory)
        assertTrue(alarmIntent.autoCompleteSingleTurn)
        assertNotNull(alarmIntent.fastDecision)
        assertEquals("set_alarm", alarmIntent.fastDecision!!.toolName)
        assertEquals(9, alarmIntent.fastDecision!!.toolArgs.getInt("hour"))
        assertEquals(0, alarmIntent.fastDecision!!.toolArgs.getInt("minute"))
        assertNotNull(alarmIntent.structuredCard)
    }

    @Test
    fun testEverydayOsAction_explicitNumericAlarmAndTimerVariants() {
        val timerIntent = IntentDecomposerAndRouter.decompose("라면 타이머 3분 맞춰 줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, timerIntent.primaryCategory)
        assertEquals("set_timer", timerIntent.fastDecision?.toolName)
        assertEquals(180, timerIntent.fastDecision?.toolArgs?.getInt("seconds"))
        assertNotNull(timerIntent.structuredCard)

        val quickTimer = IntentDecomposerAndRouter.decompose("3분 타이머 맞춰줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, quickTimer.primaryCategory)
        assertEquals("set_timer", quickTimer.fastDecision?.toolName)
        assertEquals(180, quickTimer.fastDecision?.toolArgs?.getInt("seconds"))

        val pmHalfAlarm = IntentDecomposerAndRouter.decompose("오후 7시 반 알람 설정해줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, pmHalfAlarm.primaryCategory)
        assertEquals("set_alarm", pmHalfAlarm.fastDecision?.toolName)
        assertEquals(19, pmHalfAlarm.fastDecision?.toolArgs?.getInt("hour"))
        assertEquals(30, pmHalfAlarm.fastDecision?.toolArgs?.getInt("minute"))

        val colonAlarm = IntentDecomposerAndRouter.decompose("06:45 알람 맞춰줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, colonAlarm.primaryCategory)
        assertEquals("set_alarm", colonAlarm.fastDecision?.toolName)
        assertEquals(6, colonAlarm.fastDecision?.toolArgs?.getInt("hour"))
        assertEquals(45, colonAlarm.fastDecision?.toolArgs?.getInt("minute"))
    }

    @Test
    fun testOfflineFallbackAndSetOfMarksAndCustomEndpointNormalization() {
        val chatGptSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.openai.chatgpt",
            activeWindowTitle = "ChatGPT",
            indexedElements = listOf(
                uiElement(
                    index = 20,
                    shortClass = "EditText",
                    text = "메시지",
                    contentDesc = "",
                    resourceId = "",
                    clickable = true,
                    left = 60,
                    top = 1900,
                    right = 900,
                    bottom = 2050,
                    centerX = 480,
                    centerY = 1975,
                    normX = 444,
                    normY = 844,
                    packageName = "com.openai.chatgpt"
                ).copy(editable = true, focused = true)
            )
        )

        // When offline, inferLocalOnDeviceAction MUST NEVER type into ChatGPT or click blindly — returns ask_user
        val client = GeminiMultimodalClient()
        val turn1Decision = client.inferLocalOnDeviceAction(
            goal = "배달의 민족 앱을 켜서 족발 검색해 봐",
            iteration = 1,
            snapshot = chatGptSnapshot,
            historySteps = emptyList(),
            previousPlan = emptyList()
        )
        assertEquals("ask_user", turn1Decision.toolName)
        assertNotEquals("input_text", turn1Decision.toolName)

        // Verify Set-of-Marks visual grounding placements and Custom OpenAI-compatible URL normalization
        val badges = SetOfMarksAnnotator.computeBadgePlacements(
            elements = chatGptSnapshot.indexedElements,
            screenWidth = 1080,
            screenHeight = 2340,
            bitmapWidth = 540,
            bitmapHeight = 1170
        )
        assertEquals(1, badges.size)
        assertEquals(20, badges[0].elementIndex)

        assertEquals(
            "http://192.168.0.10:11434/v1/chat/completions",
            GeminiMultimodalClient.normalizeChatCompletionsEndpoint("http://192.168.0.10:11434/v1")
        )
        assertEquals(
            "http://127.0.0.1:8000/v1/chat/completions",
            GeminiMultimodalClient.normalizeChatCompletionsEndpoint("http://127.0.0.1:8000/")
        )
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?) {
        org.junit.Assert.assertNotEquals(unexpected, actual)
    }
}
