package com.hanson.mobileuse

import android.content.Context
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.ExecutionLaneMode
import com.hanson.mobileuse.agent.MobileAiRouteTier
import com.hanson.mobileuse.agent.MobileUseAgentEngine
import com.hanson.mobileuse.agent.ParallelTaskSlot
import com.hanson.mobileuse.agent.PermissionHubManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive E2E / Integration Test Suite for Milestone 3 & Features 18, 19, 20, 21, 22:
 * - Feature 18: Multi-Tier Model Infrastructure Router (`MobileAiRouteTier`)
 * - Feature 19: Dynamic Task Complexity & Execution Lane Modes
 * - Feature 20: FG/BG Parallel Lanes (Non-blocking live chat and autonomous BG tasks)
 * - Feature 21: Compound Goal Splitting ("동시에", "하면서", ";", "||")
 * - Feature 22: Perception Serialization & Concurrent Snapshot Safety
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FgBgParallelOrchestrationTest {

    private lateinit var dummyContext: Context
    private lateinit var permissionHub: PermissionHubManager
    private lateinit var engine: MobileUseAgentEngine

    private lateinit var splitGoalsMethod: Method
    private lateinit var isBgGoalMethod: Method
    private lateinit var extractLaneMethod: Method

    @Before
    fun setUp() {
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
        }

        permissionHub = PermissionHubManager(dummyContext)
        engine = MobileUseAgentEngine(dummyContext, permissionHub)

        // Bind private reflective helper methods for direct algorithmic evaluation
        splitGoalsMethod = MobileUseAgentEngine::class.java.getDeclaredMethod("splitCompoundParallelGoals", String::class.java).apply {
            isAccessible = true
        }
        isBgGoalMethod = MobileUseAgentEngine::class.java.getDeclaredMethod("isBackgroundParallelizableGoal", String::class.java).apply {
            isAccessible = true
        }
        extractLaneMethod = MobileUseAgentEngine::class.java.getDeclaredMethod("extractExplicitLanePrefix", String::class.java).apply {
            isAccessible = true
        }
    }

    @After
    fun tearDown() {
        engine.stopRun("테스트 완료 정리")
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeSplit(goal: String): List<String> =
        splitGoalsMethod.invoke(engine, goal) as List<String>

    private fun invokeIsBgGoal(goal: String): Boolean =
        isBgGoalMethod.invoke(engine, goal) as Boolean

    @Suppress("UNCHECKED_CAST")
    private fun invokeExtractLane(goal: String): Pair<ExecutionLaneMode, String> =
        extractLaneMethod.invoke(engine, goal) as Pair<ExecutionLaneMode, String>

    // =========================================================================
    // Tier 1: Compound Goal Splitting (Feature 21)
    // =========================================================================

    @Test
    fun testCompoundGoalSplitting_splitsOnDongsiAndHamkkei() {
        val compoundPrompt = "내 폰에 무슨 게임 깔려있는지 확인하면서 동시에 카카오톡 열어서 팀 단톡방에 사족 로봇 제어라고 쳐줘"
        val subGoals = invokeSplit(compoundPrompt)

        assertEquals("Should split into exactly 2 subgoals", 2, subGoals.size)
        assertTrue(subGoals[0].contains("게임 깔려있는지 확인"))
        assertTrue(subGoals[1].contains("카카오톡 열어서"))
    }

    @Test
    fun testCompoundGoalSplitting_splitsOnSemicolonAndDoublePipe() {
        val semicolonPrompt = "배경화면 캡처해줘; 알림 내역 요약 브리핑해줘"
        val sub1 = invokeSplit(semicolonPrompt)
        assertEquals(2, sub1.size)
        assertEquals("배경화면 캡처해줘", sub1[0])
        assertEquals("알림 내역 요약 브리핑해줘", sub1[1])

        val pipePrompt = "화면 밝기 50%로 설정 || 쿠팡 장바구니 확인"
        val sub2 = invokeSplit(pipePrompt)
        assertEquals(2, sub2.size)
        assertEquals("화면 밝기 50%로 설정", sub2[0])
        assertEquals("쿠팡 장바구니 확인", sub2[1])
    }

    @Test
    fun testCompoundGoalSplitting_splitsOnHamyeonseoOnly() {
        val prompt = "설치된 앱 스캔하면서 배민 주문 확인해줘"
        val subGoals = invokeSplit(prompt)
        assertEquals(2, subGoals.size)
        assertTrue(subGoals[0].contains("설치된 앱 스캔"))
        assertTrue(subGoals[1].contains("배민 주문 확인해줘"))
    }

    @Test
    fun testCompoundGoalSplitting_returnsSingleItemForSimpleGoal() {
        val simple = "카카오톡 실행해줘"
        val subGoals = invokeSplit(simple)
        assertEquals(1, subGoals.size)
        assertEquals("카카오톡 실행해줘", subGoals[0])
    }

    // =========================================================================
    // Tier 1: Lane Classification (BG Parallelizable vs FG GUI Lane) (Feature 20)
    // =========================================================================

    @Test
    fun testLaneClassification_identifiesBackgroundSafeGoals() {
        val bgGoals = listOf(
            "내 폰에 무슨 게임 깔려있는지 찾아봐",
            "설치된 앱 목록 스캔해줘",
            "최근 알림 확인하고 요약해줘",
            "소리 볼륨 30%로 맞춰줘",
            "화면 밝기 70%로 설정",
            "코드로 그려줘",
            "브라우저로 해커뉴스 요약해줘"
        )

        for (goal in bgGoals) {
            assertTrue("Goal should be BG-parallelizable: '$goal'", invokeIsBgGoal(goal))
        }
    }

    @Test
    fun testLaneClassification_identifiesForegroundGuiGoals() {
        val fgGoals = listOf(
            "카카오톡 열어서 팀 단톡방에 메시지 보내줘",
            "배달의민족 앱 켜서 치킨 주문해줘",
            "토스 켜서 10000원 송금해줘",
            "유튜브 열고 슈카월드 재생해줘"
        )

        for (goal in fgGoals) {
            assertFalse("Goal requires interactive FG GUI: '$goal'", invokeIsBgGoal(goal))
        }
    }

    @Test
    fun testExtractExplicitLanePrefix_parsesExplicitTags() {
        val (lane1, clean1) = invokeExtractLane("[BG] 알림 요약")
        assertEquals(ExecutionLaneMode.BACKGROUND, lane1)
        assertEquals("알림 요약", clean1)

        val (lane2, clean2) = invokeExtractLane("[FG] 카톡 전송")
        assertEquals(ExecutionLaneMode.FOREGROUND, lane2)
        assertEquals("카톡 전송", clean2)

        val (lane3, clean3) = invokeExtractLane("일반 자연어 명령")
        assertEquals(ExecutionLaneMode.AUTO, lane3)
        assertEquals("일반 자연어 명령", clean3)
    }

    // =========================================================================
    // Tier 1: Parallel Lane Execution & Non-Blocking FG Chat (Feature 20)
    // =========================================================================

    @Test
    fun testParallelOrchestration_nonBlockingLiveChatDuringBackgroundExecution() {
        // Given: Send a compound goal that spawns a background job
        val compound = "내 폰에 무슨 게임 깔려있는지 확인하면서 동시에 카카오톡 실행해줘"
        engine.startRun(compound, appendUserBubble = true, laneMode = ExecutionLaneMode.AUTO)

        // When: User concurrently sends live chat messages while engine is processing
        engine.sendChatMessage("지금 무슨 작업 중이야?")
        engine.sendChatMessage("배터리 얼마나 남아있어?")

        // Then: Live chat messages must be appended to the state flow immediately without blocking
        val messages = engine.chatMessages.value
        assertTrue("Chat must contain at least 3 messages (1 initial user + 2 concurrent)", messages.size >= 3)
        val userMessages = messages.filter { it.sender == ChatSender.USER }
        assertTrue(userMessages.any { it.text.contains("게임 깔려있는지") })
        assertTrue(userMessages.any { it.text.contains("지금 무슨 작업 중이야?") })
        assertTrue(userMessages.any { it.text.contains("배터리 얼마나 남아있어?") })
    }

    @Test
    fun testParallelOrchestration_stopCommandCleansUpAllTasks() {
        engine.startRun("내 폰에 무슨 게임 깔려있는지 확인", appendUserBubble = true, laneMode = ExecutionLaneMode.BACKGROUND)

        // When user says "멈춰"
        engine.startRun("잠깐만 멈춰", appendUserBubble = true)

        // Then session enters STOPPED status and parallel tasks clear
        val session = engine.currentSession.value
        assertEquals(AgentRunStatus.STOPPED, session.status)
        assertTrue(engine.activeParallelTasks.value.isEmpty())
    }

    // =========================================================================
    // Tier 1: Multi-Tier Model Infrastructure Router (Feature 18)
    // =========================================================================

    @Test
    fun testMultiTierModelRouter_exposesAllTiersAndRefreshesState() {
        val router = engine.aiInfrastructureRouter
        val state = router.state.value

        assertNotNull(state)
        assertEquals(MobileAiRouteTier.AUTO_SMART, state.activeTier)

        // Test changing preferred tier
        router.setPreferredTier(MobileAiRouteTier.S25_AICORE_NANO)
        assertEquals(MobileAiRouteTier.S25_AICORE_NANO, router.state.value.activeTier)

        router.setPreferredTier(MobileAiRouteTier.CLOUD_GEMINI_VLM)
        assertEquals(MobileAiRouteTier.CLOUD_GEMINI_VLM, router.state.value.activeTier)
    }

    // =========================================================================
    // Tier 2: Boundary & Corner Cases (Empty Goals, Special Characters, Concurrency)
    // =========================================================================

    @Test
    fun testCompoundGoalSplitting_handlesEmptyAndWhitespaceStrings() {
        assertEquals(emptyList<String>(), invokeSplit(""))
        assertEquals(emptyList<String>(), invokeSplit("   "))
        assertEquals(emptyList<String>(), invokeSplit("\n\t"))
    }

    @Test
    fun testCompoundGoalSplitting_handlesMultipleConsecutiveDelimiters() {
        val prompt = "앱 확인 ;;; || 동시에 알림 요약"
        val result = invokeSplit(prompt)
        assertTrue("Must split cleanly without crashing: $result", result.size >= 2)
    }

    @Test
    fun testParallelTaskSlot_dataClassImmutabilityAndDefaults() {
        val slot = ParallelTaskSlot(
            taskId = "test_slot_001",
            lane = "BG",
            goal = "백그라운드 스캔"
        )
        assertEquals("test_slot_001", slot.taskId)
        assertEquals("BG", slot.lane)
        assertEquals("백그라운드 스캔", slot.goal)
        assertTrue(slot.startedAtMs > 0)
    }

    // =========================================================================
    // Tier 3 & 4: Perception Serialization & Concurrent Thread Safety (Feature 22)
    // =========================================================================

    @Test
    fun testPerceptionSerialization_atomicSnapshotThreadSafetyUnderConcurrency() {
        val numThreads = 8
        val iterationsPerThread = 25
        val latch = CountDownLatch(numThreads)
        val successCount = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(numThreads)

        val elements = (0..30).map { i ->
            IndexedUiElement(
                index = i,
                shortClass = "TextView",
                className = "android.widget.TextView",
                packageName = "com.hanson.mobileuse",
                text = "Element #$i",
                contentDesc = "Desc #$i",
                resourceId = "com.hanson:id/elem_$i",
                left = i * 20,
                top = i * 40,
                right = i * 20 + 100,
                bottom = i * 40 + 50,
                centerX = i * 20 + 50,
                centerY = i * 40 + 25,
                normX = i * 25,
                normY = i * 25,
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
        }

        val baseSnapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.hanson.mobileuse",
            activeWindowTitle = "Command Center",
            windowCount = 1,
            indexedElements = elements
        )

        for (t in 0 until numThreads) {
            pool.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        // Concurrent serialization to prompt context and JSON
                        val prompt = baseSnapshot.toPromptContext(maxElements = 20)
                        val json = baseSnapshot.toJson(includeFullTree = false)
                        if (prompt.contains("Element #0") && json.optInt("elementCount") == 31) {
                            successCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("All concurrent snapshot queries should finish within 10s", completed)
        assertEquals(numThreads * iterationsPerThread, successCount.get())
        pool.shutdownNow()
    }
}
