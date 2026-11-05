package com.hanson.mobileuse

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.CoordinateNormalizer
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.SkillCatalog
import com.hanson.mobileuse.agent.SubGoalStatus
import com.hanson.mobileuse.agent.ToolSpecRegistry
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import org.junit.Assert.assertEquals
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
    fun toolSpecRegistry_exposesAll21MobileUseToolsAndValidGeminiSchema() {
        val specs = ToolSpecRegistry.allSpecs
        assertEquals(21, specs.size)
        assertNotNull(ToolSpecRegistry.find("click"))
        assertNotNull(ToolSpecRegistry.find("click_sequence"))
        assertNotNull(ToolSpecRegistry.find("reply_notification"))
        assertNotNull(ToolSpecRegistry.find("control_device_setting"))
        assertNotNull(ToolSpecRegistry.find("manage_app"))

        val geminiJson = ToolSpecRegistry.toGeminiToolsJson()
        assertEquals(1, geminiJson.length())
        val declarations = geminiJson.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(21, declarations.length())
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
}
