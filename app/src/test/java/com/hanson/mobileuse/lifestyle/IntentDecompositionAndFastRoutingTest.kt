package com.hanson.mobileuse.lifestyle

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.AgentToolExecutionHarness
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.IntentCategory
import com.hanson.mobileuse.agent.IntentDecomposerAndRouter
import com.hanson.mobileuse.agent.LifestyleTaskModality
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.ScrollStepSize
import com.hanson.mobileuse.agent.SpatialHint
import com.hanson.mobileuse.agent.inferModalityFromPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Unified Intent Decomposer & Zero-Latency Action Router (`IntentDecomposerAndRouter`),
 * including exact regression coverage for real-device Galaxy S25 user transcripts.
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
        normY: Int
    ): IndexedUiElement = IndexedUiElement(
        index = index,
        shortClass = shortClass,
        className = "android.view.$shortClass",
        packageName = "com.google.android.youtube",
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
                    contentDesc = "내 잘못 VS 도둑 잘못 [기시니 스릴러툰] - 6분 54초 - 기시니 스릴러툰 채널로 이동 - 기시니 스릴러툰, 인증됨 - 조회수 28만회 - 1년 전 - 동영상 재생",
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
    fun testReflex_oneNotchScrollDown_executesSingleTurnWithoutVerboseChatter() {
        val intent = IntentDecomposerAndRouter.decompose("한칸 내려줘", sampleYouTubeSnapshot())
        assertEquals(IntentCategory.REFLEX_MICRO_CONTROL, intent.primaryCategory)
        assertEquals("up", intent.scrollDirection)
        assertEquals(ScrollStepSize.SMALL_ONE_NOTCH, intent.scrollStepSize)
        assertTrue(intent.autoCompleteSingleTurn)
        assertNotNull(intent.fastDecision)
        assertEquals("swipe", intent.fastDecision!!.toolName)
        assertEquals("아래로 한 칸 내렸어요.", intent.fastDecision!!.spokenReply)
        assertFalse(intent.fastDecision!!.spokenReply.contains("살펴볼게요"))
        assertFalse(intent.fastDecision!!.spokenReply.contains("실행하여"))
    }

    @Test
    fun testReflex_spacedScrollDown_executesSingleTurn() {
        val intent = IntentDecomposerAndRouter.decompose("밑으로 내려 봐", sampleYouTubeSnapshot())
        assertEquals(IntentCategory.REFLEX_MICRO_CONTROL, intent.primaryCategory)
        assertEquals("up", intent.scrollDirection)
        assertEquals(ScrollStepSize.NORMAL, intent.scrollStepSize)
        assertTrue(intent.autoCompleteSingleTurn)
        assertEquals("swipe", intent.fastDecision?.toolName)
    }

    @Test
    fun testScreenTarget_deicticUpperYouTubeVideoClick_matchesLongAccessibilityLabel() {
        val snap = sampleYouTubeSnapshot()
        val raw = "저 위에 그 내 잘못 vs 도둑 잘못 쳐 영상 클릭해 줄래"
        val cleaned = IntentDecomposerAndRouter.cleanScreenTargetQuery(raw)
        assertEquals("내 잘못 vs 도둑 잘못", cleaned)

        val intent = IntentDecomposerAndRouter.decompose(raw, snap)
        assertEquals(IntentCategory.SCREEN_ELEMENT_TARGET, intent.primaryCategory)
        assertEquals(SpatialHint.UPPER, intent.spatialHint)
        assertTrue(intent.autoCompleteSingleTurn)
        assertNotNull(intent.fastDecision)
        assertEquals("click", intent.fastDecision!!.toolName)
        assertEquals(2, intent.fastDecision!!.toolArgs.getInt("element_index"))
    }

    @Test
    fun testScreenTarget_voiceSttPhoneticTypo_matchesViaHangulJamoSlidingWindow() {
        val snap = sampleYouTubeSnapshot()
        // Real S25 STT transcript: "기시니 스릴러툰 저거 영상 눌러 줘" -> "귀신이 스릴노턴 적어 영상 눌러 줘"
        val raw = "귀신이 스릴노턴 적어 영상 눌러 줘"
        val cleaned = IntentDecomposerAndRouter.cleanScreenTargetQuery(raw)
        assertEquals("귀신이 스릴노턴", cleaned)

        val score = IntentDecomposerAndRouter.computeQueryToElementMatchScore(
            cleaned,
            snap.indexedElements[1].contentDesc
        )
        assertTrue("Expected phonetic Jamo similarity >= 0.62 but was $score", score >= 0.62)

        val matched = AgentToolExecutionHarness.findBestMatchingElement(snap, raw)
        assertNotNull(matched)
        assertEquals(2, matched!!.index)

        val intent = IntentDecomposerAndRouter.decompose(raw, snap)
        assertEquals(IntentCategory.SCREEN_ELEMENT_TARGET, intent.primaryCategory)
        assertEquals("click", intent.fastDecision?.toolName)
        assertEquals(2, intent.fastDecision?.toolArgs?.getInt("element_index"))
    }

    @Test
    fun testPlayVideo_neverHijackedByCodeAssistantMp4Synthesis() {
        val snap = sampleYouTubeSnapshot()
        val raw = "영상을 틀어 줘"

        assertFalse(
            "Playback command '영상을 틀어 줘' must NEVER trigger Code Assistant MP4 synthesis!",
            IntentDecomposerAndRouter.isExplicitGenerativeOrCodeRequest(raw, raw.lowercase(), snap, false)
        )
        assertEquals(
            LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION,
            inferModalityFromPrompt(raw)
        )

        val history = listOf(
            LiveChatMessage(sender = ChatSender.USER, text = "저 위에 그 내 잘못 vs 도둑 잘못 쳐 영상 클릭해 줄래")
        )
        val intent = IntentDecomposerAndRouter.decompose(raw, snap, history, isMainActivityInForeground = false)
        assertEquals(IntentCategory.SCREEN_ELEMENT_TARGET, intent.primaryCategory)
        assertEquals("click", intent.fastDecision?.toolName)
        assertEquals(2, intent.fastDecision?.toolArgs?.getInt("element_index"))
    }

    @Test
    fun testConversationalCorrection_neverParsedAsSearchQuery() {
        val snap = sampleYouTubeSnapshot()
        val history = listOf(
            LiveChatMessage(sender = ChatSender.USER, text = "저 위에 그 내 잘못 vs 도둑 잘못 쳐 영상 클릭해 줄래")
        )

        val correction1 = "아니 클릭해서 틀어 주면 되잖아"
        val correction2 = "아니 아까 말했던 그 영상을 클릭해 봐"

        assertNull(IntentDecomposerAndRouter.extractSafeSearchQueryIfExplicit(correction1))
        assertNull(IntentDecomposerAndRouter.extractSafeSearchQueryIfExplicit(correction2))
        assertEquals("", IntentDecomposerAndRouter.cleanScreenTargetQuery(correction1))
        assertEquals("", IntentDecomposerAndRouter.cleanScreenTargetQuery(correction2))

        val intent1 = IntentDecomposerAndRouter.decompose(correction1, snap, history, isMainActivityInForeground = false)
        assertEquals(IntentCategory.SCREEN_ELEMENT_TARGET, intent1.primaryCategory)
        assertTrue(intent1.isContextualFollowUp)
        assertEquals("click", intent1.fastDecision?.toolName)
        assertEquals(2, intent1.fastDecision?.toolArgs?.getInt("element_index"))

        val intent2 = IntentDecomposerAndRouter.decompose(correction2, snap, history, isMainActivityInForeground = false)
        assertEquals(IntentCategory.SCREEN_ELEMENT_TARGET, intent2.primaryCategory)
        assertTrue(intent2.isContextualFollowUp)
        assertEquals("click", intent2.fastDecision?.toolName)
        assertEquals(2, intent2.fastDecision?.toolArgs?.getInt("element_index"))
    }

    @Test
    fun testYouTubeQuotedSearchQuery_cleanlyExtractsTargetTitle() {
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

        val intent = IntentDecomposerAndRouter.decompose(
            rawPrompt = "유튜브 '아이유 밤편지' 영상 틀어줘",
            snapshot = null,
            isMainActivityInForeground = true
        )
        assertEquals(IntentCategory.MEDIA_SEARCH_AND_PLAY, intent.primaryCategory)
        assertEquals("아이유 밤편지", intent.cleanTargetOrSearchQuery)
    }

    @Test
    fun testExplicitGenerativeRequests_routeToGenerativeCategory() {
        val chartIntent = IntentDecomposerAndRouter.decompose("코드로 주간 매출 차트 그려줘")
        assertEquals(IntentCategory.GENERATIVE_CODE_OR_MEDIA, chartIntent.primaryCategory)

        val glbIntent = IntentDecomposerAndRouter.decompose("오리 Duck.glb 파일 받아서 3D로 띄워줘")
        assertEquals(IntentCategory.GENERATIVE_CODE_OR_MEDIA, glbIntent.primaryCategory)

        val motionIntent = IntentDecomposerAndRouter.decompose("노을 풍경 영상 모션 생성해줘")
        assertEquals(IntentCategory.GENERATIVE_CODE_OR_MEDIA, motionIntent.primaryCategory)
        assertEquals(LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA, inferModalityFromPrompt("노을 풍경 영상 모션 생성해줘"))

        val dutchPayIntent = IntentDecomposerAndRouter.decompose("코드로 더치페이 정산 미니앱 만들어줘")
        assertEquals(IntentCategory.GENERATIVE_CODE_OR_MEDIA, dutchPayIntent.primaryCategory)
        assertTrue(
            IntentDecomposerAndRouter.isExplicitGenerativeOrCodeRequest(
                "코드로 더치페이 정산 미니앱 만들어줘",
                "코드로 더치페이 정산 미니앱 만들어줘",
                null,
                true
            )
        )
    }

    @Test
    fun testEverydayOsAction_alarm9Am_neverClicksHansonOwnChatBubble() {
        // Reproduce exact Galaxy S25 forensic bug: user says "9시 알람 맞춰 줘" while Hanson app is in foreground,
        // and the snapshot contains the user's own chat bubble with text="9시 알람 맞춰 줘".
        val hansonSelfSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.hanson.mobileuse.debug",
            activeWindowTitle = "Hanson",
            indexedElements = listOf(
                IndexedUiElement(
                    index = 1,
                    shortClass = "TextView",
                    className = "android.widget.TextView",
                    packageName = "com.hanson.mobileuse.debug",
                    text = "9시 알람 맞춰 줘",
                    contentDesc = "",
                    resourceId = "",
                    left = 240,
                    top = 900,
                    right = 1020,
                    bottom = 1010,
                    centerX = 630,
                    centerY = 955,
                    normX = 583,
                    normY = 408,
                    clickable = true,
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
    fun testEverydayOsAction_timerAndFlashlightAndRinger() {
        val timerIntent = IntentDecomposerAndRouter.decompose("라면 타이머 3분 맞춰 줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, timerIntent.primaryCategory)
        assertEquals("set_timer", timerIntent.fastDecision?.toolName)
        assertEquals(180, timerIntent.fastDecision?.toolArgs?.getInt("seconds"))
        assertNotNull(timerIntent.structuredCard)

        val torchIntent = IntentDecomposerAndRouter.decompose("손전등 켜줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, torchIntent.primaryCategory)
        assertEquals("control_device_setting", torchIntent.fastDecision?.toolName)
        assertEquals("flashlight", torchIntent.fastDecision?.toolArgs?.getString("setting"))
        assertEquals("on", torchIntent.fastDecision?.toolArgs?.getString("value"))

        val vibrateIntent = IntentDecomposerAndRouter.decompose("진동 모드로 바꿔줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, vibrateIntent.primaryCategory)
        assertEquals("control_device_setting", vibrateIntent.fastDecision?.toolName)
        assertEquals("ringer_mode", vibrateIntent.fastDecision?.toolArgs?.getString("setting"))
        assertEquals("vibrate", vibrateIntent.fastDecision?.toolArgs?.getString("value"))
    }

    @Test
    fun testCompoundLifestyleRoutine_sleepAndFocus() {
        val sleepIntent = IntentDecomposerAndRouter.decompose("잘 준비 해줘")
        assertEquals(IntentCategory.COMPOUND_LIFESTYLE_ROUTINE, sleepIntent.primaryCategory)
        assertTrue(sleepIntent.autoCompleteSingleTurn)
        assertEquals("execute_lifestyle_routine", sleepIntent.fastDecision?.toolName)
        assertEquals("sleep", sleepIntent.fastDecision?.toolArgs?.getString("routine"))

        val focusIntent = IntentDecomposerAndRouter.decompose("집중 모드 켜줘")
        assertEquals(IntentCategory.COMPOUND_LIFESTYLE_ROUTINE, focusIntent.primaryCategory)
        assertTrue(focusIntent.autoCompleteSingleTurn)
        assertEquals("execute_lifestyle_routine", focusIntent.fastDecision?.toolName)
        assertEquals("focus", focusIntent.fastDecision?.toolArgs?.getString("routine"))
    }

    @Test
    fun testBaeminCompoundSearch_andHomeAndCloseInputReflex_neverTypesIntoChatGpt() {
        // 1. "홈 화면으로 나가 봐" -> REFLEX_MICRO_CONTROL, press_key("HOME"), 1-turn completion
        val homeIntent = IntentDecomposerAndRouter.decompose("홈 화면으로 나가 봐")
        assertEquals(IntentCategory.REFLEX_MICRO_CONTROL, homeIntent.primaryCategory)
        assertTrue(homeIntent.autoCompleteSingleTurn)
        assertEquals("press_key", homeIntent.fastDecision?.toolName)
        assertEquals("HOME", homeIntent.fastDecision?.toolArgs?.getString("key"))

        // 2. "컴퓨터 입력 창을 닫아 봐" -> REFLEX_MICRO_CONTROL, press_key("BACK"), 1-turn completion
        val closeInputIntent = IntentDecomposerAndRouter.decompose("컴퓨터 입력 창을 닫아 봐")
        assertEquals(IntentCategory.REFLEX_MICRO_CONTROL, closeInputIntent.primaryCategory)
        assertTrue(closeInputIntent.autoCompleteSingleTurn)
        assertEquals("press_key", closeInputIntent.fastDecision?.toolName)
        assertEquals("BACK", closeInputIntent.fastDecision?.toolArgs?.getString("key"))

        // 3. Compound Baemin prompts (spaced & conversational correction) extract ("배달의민족", "com.sampleapp") and "족발"
        val (app1, q1) = IntentDecomposerAndRouter.extractCleanAppAndSearchQuery("배달의 민족 앱 켜서 족발 검색해 봐")
        assertEquals("배달의민족", app1?.first)
        assertEquals("com.sampleapp", app1?.second)
        assertEquals("족발", q1)

        val (app2, q2) = IntentDecomposerAndRouter.extractCleanAppAndSearchQuery("배달의 민족 앱을 켜서 족발 검색해 봐")
        assertEquals("배달의민족", app2?.first)
        assertEquals("com.sampleapp", app2?.second)
        assertEquals("족발", q2)

        val (app3, q3) = IntentDecomposerAndRouter.extractCleanAppAndSearchQuery(
            "이건 체지피티에 프롬프트 입력창에 넣은 거고 배달의 민족 앱이 있어 그거를 열어서 족발을 검색해 봐"
        )
        assertEquals("배달의민족", app3?.first)
        assertEquals("com.sampleapp", app3?.second)
        assertEquals("족발", q3)

        // 4. When on the ChatGPT screen with an editable input box, inferLocalOnDeviceAction MUST launch 배달의민족, NEVER type into ChatGPT!
        val chatGptSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.openai.chatgpt",
            activeWindowTitle = "ChatGPT",
            indexedElements = listOf(
                IndexedUiElement(
                    index = 20,
                    shortClass = "EditText",
                    className = "android.widget.EditText",
                    packageName = "com.openai.chatgpt",
                    text = "메시지",
                    contentDesc = "",
                    resourceId = "",
                    left = 60,
                    top = 1900,
                    right = 900,
                    bottom = 2050,
                    centerX = 480,
                    centerY = 1975,
                    normX = 444,
                    normY = 844,
                    clickable = true,
                    longClickable = false,
                    scrollable = false,
                    editable = true,
                    checkable = false,
                    checked = false,
                    focused = true,
                    enabled = true,
                    windowType = "APPLICATION",
                    windowLayer = 0
                )
            )
        )

        val client = com.hanson.mobileuse.agent.GeminiMultimodalClient()
        val turn1Decision = client.inferLocalOnDeviceAction(
            goal = "배달의 민족 앱을 켜서 족발 검색해 봐",
            iteration = 1,
            snapshot = chatGptSnapshot,
            historySteps = emptyList(),
            previousPlan = emptyList()
        )
        assertEquals("manage_app", turn1Decision.toolName)
        assertEquals("launch", turn1Decision.toolArgs.getString("action"))
        assertEquals("배달의민족", turn1Decision.toolArgs.getString("app_name"))
    }

    @Test
    fun testBaeminModalObstacleDismissal_andPostconditionCompletion_andSetOfMarksAndCustomUrl() {
        // 1. "어 이거 지금 창 닫고 그 족발 검색해 볼래" -> extracts clean query "족발"
        val (_, cleanQ) = IntentDecomposerAndRouter.extractCleanAppAndSearchQuery("어 이거 지금 창 닫고 그 족발 검색해 볼래")
        assertEquals("족발", cleanQ)

        // 2. "배달의 민족의 켜 볼래" -> single-step pure app launch
        val launchIntent = IntentDecomposerAndRouter.decompose("배달의 민족의 켜 볼래")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, launchIntent.primaryCategory)
        assertTrue(launchIntent.autoCompleteSingleTurn)
        assertEquals("manage_app", launchIntent.fastDecision?.toolName)
        assertEquals("배달의민족", launchIntent.fastDecision?.toolArgs?.getString("app_name"))

        // 3. Baemin screen covered by '배민클럽 무료 이용기간' modal ('닫기') when user says "족발 검색해 줘":
        //    - Turn 1: clicks '닫기' (popup close)
        //    - Turn 2 (home screen now shows '먹고 싶은 메뉴, 가게 검색'): clicks search bar (NEVER prematurely calling complete_task!)
        //    - Turn 3 (editable search input open): types "족발"
        //    - Turn 4 (after typing "족발"): verifies postcondition and calls complete_task
        val baeminModalSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.sampleapp",
            activeWindowTitle = "배달의민족",
            indexedElements = listOf(
                IndexedUiElement(
                    index = 1,
                    shortClass = "Button",
                    className = "android.widget.Button",
                    packageName = "com.sampleapp",
                    text = "닫기",
                    contentDesc = "닫기",
                    resourceId = "com.sampleapp:id/btn_close",
                    left = 900,
                    top = 180,
                    right = 1020,
                    bottom = 260,
                    centerX = 960,
                    centerY = 220,
                    normX = 888,
                    normY = 94,
                    clickable = true,
                    longClickable = false,
                    scrollable = false,
                    editable = false,
                    checkable = false,
                    checked = false,
                    focused = false,
                    enabled = true,
                    windowType = "APPLICATION",
                    windowLayer = 0
                ),
                IndexedUiElement(
                    index = 2,
                    shortClass = "TextView",
                    className = "android.widget.TextView",
                    packageName = "com.sampleapp",
                    text = "무료 이용기간 중엔 결제되지 않아요",
                    contentDesc = "",
                    resourceId = "",
                    left = 100,
                    top = 800,
                    right = 980,
                    bottom = 920,
                    centerX = 540,
                    centerY = 860,
                    normX = 500,
                    normY = 367,
                    clickable = false,
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
            )
        )

        val client = com.hanson.mobileuse.agent.GeminiMultimodalClient()
        val t1 = client.inferLocalOnDeviceAction(
            goal = "족발 검색해 줘",
            iteration = 1,
            snapshot = baeminModalSnapshot,
            historySteps = emptyList(),
            previousPlan = emptyList()
        )
        assertEquals("click", t1.toolName)
        assertEquals(1, t1.toolArgs.getInt("element_index"))
        assertEquals("팝업 닫기", t1.toolArgs.getString("target_description"))

        val step1Log = com.hanson.mobileuse.agent.AgentStepLog(
            stepNumber = 1,
            observation = t1.observation,
            planLedger = t1.subGoals,
            toolName = "click",
            toolArgsJson = t1.toolArgs.toString(),
            resultSummary = "팝업 닫기 완료",
            succeeded = true
        )

        val baeminHomeSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = "com.sampleapp",
            activeWindowTitle = "배달의민족",
            indexedElements = listOf(
                IndexedUiElement(
                    index = 5,
                    shortClass = "ViewGroup",
                    className = "android.view.ViewGroup",
                    packageName = "com.sampleapp",
                    text = "먹고 싶은 메뉴, 가게 검색",
                    contentDesc = "검색",
                    resourceId = "com.sampleapp:id/search_bar",
                    left = 60,
                    top = 220,
                    right = 1020,
                    bottom = 340,
                    centerX = 540,
                    centerY = 280,
                    normX = 500,
                    normY = 120,
                    clickable = true,
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
            )
        )

        // Turn 2 MUST click the search bar, NOT complete_task!
        val t2 = client.inferLocalOnDeviceAction(
            goal = "족발 검색해 줘",
            iteration = 2,
            snapshot = baeminHomeSnapshot,
            historySteps = listOf(step1Log),
            previousPlan = t1.subGoals
        )
        assertEquals("click", t2.toolName)
        assertEquals(5, t2.toolArgs.getInt("element_index"))
        assertEquals("검색창 열기", t2.toolArgs.getString("target_description"))

        // 4. Verify Set-of-Marks visual grounding placements and Custom OpenAI-compatible URL normalization
        val badges = com.hanson.mobileuse.accessibility.SetOfMarksAnnotator.computeBadgePlacements(
            elements = baeminHomeSnapshot.indexedElements,
            screenWidth = 1080,
            screenHeight = 2340,
            bitmapWidth = 540,
            bitmapHeight = 1170
        )
        assertEquals(1, badges.size)
        assertEquals(5, badges[0].elementIndex)

        assertEquals(
            "http://192.168.0.10:11434/v1/chat/completions",
            com.hanson.mobileuse.agent.GeminiMultimodalClient.normalizeChatCompletionsEndpoint("http://192.168.0.10:11434/v1")
        )
        assertEquals(
            "http://127.0.0.1:8000/v1/chat/completions",
            com.hanson.mobileuse.agent.GeminiMultimodalClient.normalizeChatCompletionsEndpoint("http://127.0.0.1:8000/")
        )
    }
}
