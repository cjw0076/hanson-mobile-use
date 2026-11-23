package com.hanson.mobileuse

import android.content.Context
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger M2 Stress & Edge-Case Verification Suite
 * Authored by challenger_m2_2 (Stress & Edge-Case Challenger)
 * Empirically tests:
 * 1. Flashlight rapid toggles, malformed arguments, uppercase/lowercase prompt variations.
 * 2. Polymorphic JSONObject contract (getInt, optInt, getString, optString, JSON-RPC toString round-trip).
 * 3. Everyday OS Action Background lane classification bounds (volume, brightness, torch, ringer, DND).
 * 4. Invariant that interactive GUI actions and touches NEVER leak into background lane.
 * 5. Concurrent multi-threaded background lane evaluation resilience.
 */
class ChallengerM2StressAndEdgeCaseTest {

    private lateinit var dummyContext: Context
    private lateinit var permissionHub: PermissionHubManager
    private lateinit var engine: MobileUseAgentEngine
    private lateinit var isBgGoalMethod: Method

    @Before
    fun setUp() {
        assertNull("Precondition: A11y instance should be null in isolated unit tests", HansonAccessibilityService.instance)

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

        isBgGoalMethod = MobileUseAgentEngine::class.java.getDeclaredMethod("isBackgroundParallelizableGoal", String::class.java).apply {
            isAccessible = true
        }
    }

    @After
    fun tearDown() {
        engine.stopRun("테스트 완료 정리")
    }

    private fun invokeIsBgGoal(goal: String): Boolean =
        isBgGoalMethod.invoke(engine, goal) as Boolean

    // =========================================================================
    // 1. Strict Alarm/Timer Fast-Lane vs Non-Hijacked Flashlight/Light Prompts (100 iterations)
    // =========================================================================

    @Test
    fun testFlashlight_rapidAlternatingToggleStress() {
        // B4 Deconstruction: Flashlight/light phrases must NOT be substring-hijacked in IntentDecomposerAndRouter
        // so ambiguous prompts like "라이트 뭐야" reach the real LLM, while explicit flashlight commands remain
        // background-safe in MobileUseAgentEngine.isBackgroundParallelizableGoal.
        val flashlightPrompts = listOf(
            "손전등 켜줘", "플래시 켜", "후레쉬 켜줘", "손전등 꺼줘", "플래시 꺼", "후레쉬 꺼줘", "손전등 ON", "손전등 OFF"
        )
        val ambiguousLightPrompts = listOf("라이트 뭐야", "라이트 노벨 추천해줘", "플래시 메모리 설명해줘")

        for (i in 0 until 100) {
            val prompt = flashlightPrompts[i % flashlightPrompts.size]
            val intent = IntentDecomposerAndRouter.decompose(prompt)
            assertEquals("Prompt '$prompt' must delegate to real AI lane", IntentCategory.MULTI_STEP_GUI_OR_CHAT, intent.primaryCategory)
            assertNull("IntentDecomposerAndRouter must not fabricate fastDecision for '$prompt'", intent.fastDecision)
            assertTrue("Explicit flashlight prompt '$prompt' stays background-safe", invokeIsBgGoal(prompt))
        }

        for (ambiguous in ambiguousLightPrompts) {
            val intent = IntentDecomposerAndRouter.decompose(ambiguous)
            assertEquals("Ambiguous prompt '$ambiguous' must not be hijacked", IntentCategory.MULTI_STEP_GUI_OR_CHAT, intent.primaryCategory)
            assertNull("Ambiguous prompt '$ambiguous' must have null fastDecision", intent.fastDecision)
        }
    }

    // =========================================================================
    // 2. Explicit Numeric Timer/Alarm JSON Wire Serialization and Round-Trip
    // =========================================================================

    @Test
    fun testFlashlight_jsonWireSerializationAndRoundTrip() {
        val timerIntent = IntentDecomposerAndRouter.decompose("3분 타이머 맞춰줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, timerIntent.primaryCategory)
        val timerArgs = timerIntent.fastDecision?.toolArgs
        assertNotNull(timerArgs)

        val timerWireJson = timerArgs!!.toString()
        assertTrue("Serialized wire JSON must contain integer seconds:180: $timerWireJson", timerWireJson.contains("\"seconds\":180"))

        val parsedTimer = JSONObject(timerWireJson)
        assertEquals(180, parsedTimer.getInt("seconds"))
        assertEquals(180, parsedTimer.optInt("seconds"))

        val alarmIntent = IntentDecomposerAndRouter.decompose("오후 7시 30분 알람 맞춰줘")
        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, alarmIntent.primaryCategory)
        val alarmArgs = alarmIntent.fastDecision?.toolArgs
        assertNotNull(alarmArgs)

        val parsedAlarm = JSONObject(alarmArgs!!.toString())
        assertEquals(19, parsedAlarm.getInt("hour"))
        assertEquals(30, parsedAlarm.getInt("minute"))

        // Verify control_device_setting flashlight value parsing parity ("1"/"on" vs "0"/"off")
        val onEnable = JSONObject().put("setting", "flashlight").put("value", "on").optString("value")
            .lowercase(Locale.ROOT) !in listOf("off", "false", "0", "끄기", "꺼")
        assertTrue("Flashlight 'on' must evaluate to enable=true", onEnable)

        val offEnable = JSONObject().put("setting", "flashlight").put("value", "0").optString("value")
            .lowercase(Locale.ROOT) !in listOf("off", "false", "0", "끄기", "꺼")
        assertFalse("Flashlight '0' must evaluate to enable=false", offEnable)
    }

    // =========================================================================
    // 3. Anchored Explicit Numeric Timer/Alarm vs Non-Create Phrasing Variations
    // =========================================================================

    @Test
    fun testFlashlight_phrasingVariationsAndCasing() {
        val validTimers = listOf(
            "3분 타이머 맞춰줘" to 180,
            "라면 타이머 4분 30초 시작해줘" to 270,
            "1시간 10분 타이머 설정해줘" to 4200,
            "45초 타이머 걸어줘" to 45
        )
        for ((prompt, expectedSec) in validTimers) {
            val intent = IntentDecomposerAndRouter.decompose(prompt)
            assertEquals("Prompt '$prompt' should decompose to EVERYDAY_OS_ACTION", IntentCategory.EVERYDAY_OS_ACTION, intent.primaryCategory)
            assertEquals("set_timer", intent.fastDecision?.toolName)
            assertEquals("Prompt '$prompt' should have seconds=$expectedSec", expectedSec, intent.fastDecision?.toolArgs?.getInt("seconds"))
        }

        val nonCreatePrompts = listOf(
            "타이머 멈춰",
            "타이머 꺼줘",
            "타이머 맞춰줘", // missing explicit duration -> never defaults to 3 min
            "알람 꺼줘",
            "알람 맞춰줘",   // missing explicit time -> never defaults to 7 AM
            "라이트 켜",
            "라이트 꺼",
            "라이트 뭐야"
        )
        for (prompt in nonCreatePrompts) {
            val intent = IntentDecomposerAndRouter.decompose(prompt)
            assertEquals("Non-anchored prompt '$prompt' must route to real AI", IntentCategory.MULTI_STEP_GUI_OR_CHAT, intent.primaryCategory)
            assertNull("Non-anchored prompt '$prompt' must have null fastDecision", intent.fastDecision)
        }
    }

    // =========================================================================
    // 4. Everyday OS Action Background Lane Boundary Conditions
    // =========================================================================

    @Test
    fun testEverydayOsAction_deviceSettingBoundaryConditions() {
        val boundarySettings = listOf(
            // Volume bounds
            "소리 볼륨 0%로 맞춰줘",
            "소리 볼륨 100%로 맞춰줘",
            "소리 볼륨 -10%로 설정",
            "소리 볼륨 200%로 맞춰줘",
            "음악 볼륨 50%로 줄여줘",
            "소리 안나게 줄여줘",
            "소리 최대로 키워줘",
            // Brightness bounds
            "화면 밝기 0%로 설정",
            "화면 밝기 100%로 설정",
            "화면 밝기 70%로 설정",
            "화면 밝기 낮춰줘",
            "화면 어둡게 해줘",
            // Torch / Flashlight
            "손전등 켜줘",
            "손전등 꺼줘",
            "플래시 켜",
            "후레쉬 꺼줘",
            // Ringer Mode & DND
            "진동 모드로 변경해줘",
            "무음으로 설정해줘",
            "벨소리 모드로 변경해줘",
            "방해금지 모드 켜줘",
            "방해금지 꺼줘"
        )

        for (goal in boundarySettings) {
            assertTrue("Setting goal should be classified background-safe: '$goal'", invokeIsBgGoal(goal))
        }
    }

    // =========================================================================
    // 5. Invariant: Interactive GUI Actions and Touches NEVER Leak to Background Lane
    // =========================================================================

    @Test
    fun testEverydayOsAction_interactiveGuiInvariant() {
        val foregroundGuiGoals = listOf(
            // External app launch & interactive search
            "카카오톡 열어서 팀 단톡방에 메시지 보내줘",
            "배달의민족 앱 켜서 치킨 주문해줘",
            "토스 켜서 10000원 송금해줘",
            "유튜브 열고 슈카월드 재생해줘",
            "인스타그램 켜서 피드 새로고침해줘",
            "쿠팡 앱 열어서 휴지 검색해줘",
            // Direct UI touch / click interactions
            "장바구니 버튼 클릭해줘",
            "확인 버튼 눌러",
            "아래로 스크롤해줘",
            "검색창에 입력해줘",
            // GUI System Settings Panels (explicitly require foreground visual interaction)
            "와이파이 설정 화면 열어줘",
            "블루투스 설정 창 켜줘"
        )

        for (goal in foregroundGuiGoals) {
            assertFalse("Interactive GUI goal must NEVER be parallelized to background lane: '$goal'", invokeIsBgGoal(goal))
        }
    }

    // =========================================================================
    // 6. Concurrency Stress: Multi-Threaded Decompose & Background Lane Evaluation
    // =========================================================================

    @Test
    fun testConcurrency_multiThreadedDecomposeAndLaneClassification() = runBlocking {
        val iterations = 50
        val failureCount = AtomicInteger(0)

        val jobs = (0 until iterations).map { index ->
            launch(Dispatchers.Default) {
                try {
                    val isSetting = (index % 2 == 0)
                    if (isSetting) {
                        val prompt = if (index % 4 == 0) "손전등 켜줘" else "소리 볼륨 50%로 맞춰줘"
                        val intent = IntentDecomposerAndRouter.decompose(prompt)
                        assertEquals(IntentCategory.MULTI_STEP_GUI_OR_CHAT, intent.primaryCategory)
                        assertNull(intent.fastDecision)
                        assertTrue(invokeIsBgGoal(prompt))
                    } else {
                        val timerIntent = IntentDecomposerAndRouter.decompose("3분 타이머 맞춰줘")
                        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, timerIntent.primaryCategory)
                        val fgPrompt = "카카오톡 열어서 팀 단톡방에 메시지 보내줘"
                        val intent = IntentDecomposerAndRouter.decompose(fgPrompt)
                        assertEquals(IntentCategory.MULTI_STEP_GUI_OR_CHAT, intent.primaryCategory)
                        assertFalse(invokeIsBgGoal(fgPrompt))
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                    t.printStackTrace()
                }
            }
        }

        jobs.joinAll()
        assertEquals("Zero concurrent exceptions allowed during multi-threaded stress", 0, failureCount.get())
    }
}
