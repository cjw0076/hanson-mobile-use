package com.hanson.mobileuse

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.CoordinateNormalizer
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.ActionTrajectoryVerifierAndHealer
import com.hanson.mobileuse.agent.AgentStepLog
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.SkillCatalog
import com.hanson.mobileuse.agent.SubGoalStatus
import com.hanson.mobileuse.agent.ToolSpecRegistry
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.paradigm.WorldModelGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MobileUseHarnessTest {

    @Before
    fun setUp() {
        RemoteInputSessionCache.clearAll()
    }

    @Test
    fun coordinateNormalizer_convertsBidirectionallyOn1000Grid() {
        val screenW = 1080
        val screenH = 2400

        val (normX, normY) = CoordinateNormalizer.pixelToNormalized(540, 1200, screenW, screenH)
        assertEquals(500, normX)
        assertEquals(500, normY)

        val (pxX, pxY) = CoordinateNormalizer.normalizedToPixel(500, 500, screenW, screenH)
        assertEquals(540, pxX)
        assertEquals(1200, pxY)
    }

    @Test
    fun coordinateNormalizer_computesDirectionalSwipeWithAnchorOverlap() {
        val upSwipe = CoordinateNormalizer.computeDirectionalSwipe("up", 1000, 2000)
        assertTrue("Swipe up starts lower than it ends", upSwipe.startY > upSwipe.endY)
        assertEquals(1440, upSwipe.startY)
        assertEquals(560, upSwipe.endY)

        val downSwipe = CoordinateNormalizer.computeDirectionalSwipe("down", 1000, 2000)
        assertTrue("Swipe down starts higher than it ends", downSwipe.startY < downSwipe.endY)
    }

    @Test
    fun toolSpecRegistry_exposesAll24MobileUseToolsAndValidGeminiSchema() {
        val specs = ToolSpecRegistry.allSpecs
        assertEquals(27, specs.size)
        assertNotNull(ToolSpecRegistry.find("click"))
        assertNotNull(ToolSpecRegistry.find("click_sequence"))
        assertNotNull(ToolSpecRegistry.find("drag_and_drop"))
        assertNotNull(ToolSpecRegistry.find("haptic_trajectory"))
        assertNotNull(ToolSpecRegistry.find("reply_notification"))
        assertNotNull(ToolSpecRegistry.find("control_device_setting"))
        assertNotNull(ToolSpecRegistry.find("manage_app"))
        assertNotNull(ToolSpecRegistry.find("os_cli_exec"))
        assertNotNull(ToolSpecRegistry.find("reverse_engineer_app"))
        assertNotNull(ToolSpecRegistry.find("learn_app_skill"))
        assertNotNull(ToolSpecRegistry.find("render_generative_ui"))

        val geminiJson = ToolSpecRegistry.toGeminiToolsJson()
        assertEquals(1, geminiJson.length())
        val declarations = geminiJson.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(27, declarations.length())
    }

    @Test
    fun geminiMultimodalClient_parses3PhaseObservationAndPlanLedger() {
        val rawModelText = """
            OBSERVATION: 카카오톡 메인 채팅 목록 화면이 열려 있으며 우측 상단에 검색 아이콘이 보입니다.
            PLAN:
            - [x] 카카오톡 앱 실행
            - [/] 상단 검색 버튼 탭하여 대화방 검색
            - [ ] 메시지 입력 및 전송
        """.trimIndent()

        val (obs, plan) = GeminiMultimodalClient.parseObservationAndPlan(rawModelText, emptyList())
        assertTrue(obs.contains("카카오톡 메인 채팅 목록"))
        assertEquals(3, plan.size)
        assertEquals(SubGoalStatus.COMPLETED, plan[0].status)
        assertEquals(SubGoalStatus.IN_PROGRESS, plan[1].status)
        assertEquals(SubGoalStatus.PENDING, plan[2].status)
    }

    @Test
    fun geminiMultimodalClient_localFallbackHandlesFastLaneAndUiMatching() {
        val client = GeminiMultimodalClient()
        val snapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.kakao.talk",
            indexedElements = listOf(
                IndexedUiElement(
                    index = 0,
                    shortClass = "ImageButton",
                    className = "android.widget.ImageButton",
                    packageName = "com.kakao.talk",
                    text = "통합검색",
                    contentDesc = "검색",
                    resourceId = "com.kakao.talk:id/search",
                    left = 900,
                    top = 100,
                    right = 1020,
                    bottom = 220,
                    centerX = 960,
                    centerY = 160,
                    normX = 889,
                    normY = 67,
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

        val replyDecision = client.inferLocalOnDeviceAction(
            goal = "최근 카톡 알림에 \"금방 갈게\"라고 답장해줘",
            iteration = 1,
            snapshot = snapshot,
            historySteps = emptyList(),
            previousPlan = emptyList()
        )
        assertEquals("reply_notification", replyDecision.toolName)
        assertEquals("금방 갈게", replyDecision.toolArgs.getString("message"))

        val clickDecision = client.inferLocalOnDeviceAction(
            goal = "화면에서 통합검색 눌러줘",
            iteration = 1,
            snapshot = snapshot,
            historySteps = emptyList(),
            previousPlan = emptyList()
        )
        assertEquals("click", clickDecision.toolName)
        assertEquals(0, clickDecision.toolArgs.getInt("element_index"))
    }

    @Test
    fun remoteInputSessionCache_storesFindsAndEvictsExpiredNotifications() {
        val now = 1_000_000L
        RemoteInputSessionCache.put(
            InterceptedNotification(
                sessionId = "talk_101",
                notificationKey = "0|com.kakao.talk|101",
                packageName = "com.kakao.talk",
                appLabel = "카카오톡",
                sender = "김민수 팀장",
                roomOrTitle = "단톡방",
                messageText = "오늘 배포 준비 완료됐나요?",
                timestampMs = now,
                canQuickReply = true
            )
        )

        val matched = RemoteInputSessionCache.findBestMatch("김민수")
        assertNotNull(matched)
        assertEquals("talk_101", matched?.sessionId)

        RemoteInputSessionCache.markReplied("talk_101", "네 완료되었습니다!")
        assertEquals("네 완료되었습니다!", RemoteInputSessionCache.get("talk_101")?.repliedText)

        val evicted = RemoteInputSessionCache.evictExpired(nowMs = now + 40 * 60 * 1000L)
        assertEquals(1, evicted)
    }

    @Test
    fun skillCatalog_hasValidPresetRoutines() {
        assertTrue(SkillCatalog.defaultSkills.size >= 6)
        assertTrue(SkillCatalog.defaultSkills.all { it.goalPrompt.isNotBlank() })
    }

    @Test
    fun trajectoryVerifierAndWorldModel_detectsStaleVsTransitionAndPlansMultiStepGoal() {
        val elSearchBtn = IndexedUiElement(
            index = 0,
            shortClass = "ImageButton",
            className = "android.widget.ImageButton",
            packageName = "com.google.android.youtube",
            text = "",
            contentDesc = "검색",
            resourceId = "com.google.android.youtube:id/menu_item_1",
            left = 900,
            top = 90,
            right = 1020,
            bottom = 210,
            centerX = 960,
            centerY = 150,
            normX = 889,
            normY = 62,
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

        val elEditBox = elSearchBtn.copy(
            index = 1,
            shortClass = "EditText",
            className = "android.widget.EditText",
            text = "YouTube 검색",
            editable = true,
            focused = true
        )

        val snapHome = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.sec.android.app.launcher",
            indexedElements = emptyList()
        )
        val snapYoutubeMain = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.google.android.youtube",
            indexedElements = listOf(elSearchBtn)
        )
        val snapYoutubeSearchOpen = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.google.android.youtube",
            indexedElements = listOf(elEditBox)
        )

        // 1. Identical snapshot produces 0.0f delta (Stale Click detection)
        val staleDelta = ActionTrajectoryVerifierAndHealer.computeUiTreeDeltaRatio(snapYoutubeMain, snapYoutubeMain)
        assertEquals(0.0f, staleDelta, 0.001f)

        // 2. Changed UI tree produces > 0.5f delta (Verified Transition)
        val validDelta = ActionTrajectoryVerifierAndHealer.computeUiTreeDeltaRatio(snapYoutubeMain, snapYoutubeSearchOpen)
        assertTrue(validDelta > 0.5f)

        // 3. WorldModelGraph produces distinct perceptual hashes for distinct states
        val hashMain = WorldModelGraph.computeStateHash(snapYoutubeMain)
        val hashSearch = WorldModelGraph.computeStateHash(snapYoutubeSearchOpen)
        assertNotEquals(hashMain, hashSearch)

        // 4. Multi-step trajectory planner progresses: Step 1 (manage_app) -> Step 2 (click search) -> Step 3 (input_text)
        val client = GeminiMultimodalClient()
        val goal = "유튜브 열어서 아이유 검색하고 클릭해줘"

        val step1 = client.inferLocalOnDeviceAction(goal, 1, snapHome, emptyList(), emptyList())
        assertEquals("manage_app", step1.toolName)

        val historyAfterStep1 = listOf(
            AgentStepLog(1, "앱 실행", emptyList(), "manage_app", "{}", "유튜브 실행 완료", true, "com.google.android.youtube", 1)
        )
        val step2 = client.inferLocalOnDeviceAction(goal, 2, snapYoutubeMain, historyAfterStep1, step1.subGoals)
        assertEquals("click", step2.toolName)

        val historyAfterStep2 = historyAfterStep1 + AgentStepLog(
            2, "검색창 탭", emptyList(), "click", "{}", "검색 버튼 탭 완료", true, "com.google.android.youtube", 1
        )
        val step3 = client.inferLocalOnDeviceAction(goal, 3, snapYoutubeSearchOpen, historyAfterStep2, step2.subGoals)
        assertEquals("input_text", step3.toolName)
        assertEquals("아이유", step3.toolArgs.getString("text"))
    }

    @Test
    fun aiStudioOfficialModelsAndInteractionsSchema_alignWith2026Docs() {
        // Official Dec 1, 2026 routing per https://aistudio.google.com/docs/models
        assertEquals("gemini-3.5-flash-lite", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-flash"))
        assertEquals("gemini-3.8-flash", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-pro"))
        assertEquals("gemini-2.5-flash-lite", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-flash-lite"))
        assertEquals("gemini-3.1-flash-lite", GeminiMultimodalClient.normalize2026ModelName("gemini-3.1-flash-lite-preview"))
        assertEquals("gemini-robotics-er-2-preview", GeminiMultimodalClient.normalize2026ModelName("gemini-robotics-er-1.6-preview"))
        assertEquals("gemini-3.8-live", GeminiMultimodalClient.normalize2026ModelName("gemini-3.1-flash-live-preview"))
        assertEquals("gemini-3.8-flash-tts", GeminiMultimodalClient.normalize2026ModelName("gemini-3.1-flash-tts-preview"))
        assertEquals("gemini-nano-banana-2.1", GeminiMultimodalClient.normalize2026ModelName("gemini-2.5-flash-image"))
        assertEquals("gemini-4-argon", GeminiMultimodalClient.normalize2026ModelName("gemini-4-argon"))

        val officialIds = GeminiMultimodalClient.AISTUDIO_OFFICIAL_MODELS.map { it.first }.toSet()
        assertTrue(officialIds.contains("gemini-4-argon"))
        assertTrue(officialIds.contains("gemini-3.8-flash"))
        assertTrue(officialIds.contains("gemini-3.6-flash"))
        assertTrue(officialIds.contains("gemini-3.5-flash-lite"))
        assertTrue(officialIds.contains("gemini-3.1-flash-lite"))
        assertTrue(officialIds.contains("gemini-robotics-er-2-preview"))
        assertTrue(officialIds.contains("antigravity-preview-09-2026"))
        assertTrue(officialIds.contains("gemini-3.8-live"))
        assertTrue(officialIds.contains("gemini-3.8-live-extended-thinking"))

        val interactionsTools = ToolSpecRegistry.toGeminiInteractionsToolsJson()
        assertEquals(27, interactionsTools.length())
        val firstTool = interactionsTools.getJSONObject(0)
        assertEquals("function", firstTool.getString("type"))
        assertEquals("object", firstTool.getJSONObject("parameters").getString("type"))
    }
}
