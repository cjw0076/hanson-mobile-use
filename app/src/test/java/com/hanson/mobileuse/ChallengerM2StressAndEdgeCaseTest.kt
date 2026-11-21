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
    // 1. Flashlight Rapid Alternating Toggle Stress (100 iterations)
    // =========================================================================

    @Test
    fun testFlashlight_rapidAlternatingToggleStress() {
        val onPrompts = listOf("손전등 켜줘", "플래시 켜", "후레쉬 켜줘", "라이트 켜", "손전등 ON", "플래시 ON")
        val offPrompts = listOf("손전등 꺼줘", "플래시 꺼", "후레쉬 꺼줘", "라이트 꺼", "손전등 OFF", "플래시 OFF", "후레쉬 해제")

        for (i in 0 until 100) {
            val isTurnOn = (i % 2 == 0)
            val prompt = if (isTurnOn) onPrompts[i % onPrompts.size] else offPrompts[i % offPrompts.size]
            val intent = IntentDecomposerAndRouter.decompose(prompt)

            assertEquals("Prompt '$prompt' should decompose to EVERYDAY_OS_ACTION", IntentCategory.EVERYDAY_OS_ACTION, intent.primaryCategory)
            assertEquals("Prompt '$prompt' should have tool control_device_setting", "control_device_setting", intent.fastDecision?.toolName)

            val toolArgs = intent.fastDecision?.toolArgs
            assertNotNull("toolArgs must not be null for '$prompt'", toolArgs)
            assertEquals("setting argument must be flashlight", "flashlight", toolArgs?.getString("setting"))

            val expectedInt = if (isTurnOn) 1 else 0
            val expectedStr = if (isTurnOn) "on" else "off"

            // Integer getter contracts
            assertEquals("getInt('value') must return $expectedInt for '$prompt'", expectedInt, toolArgs?.getInt("value"))
            assertEquals("optInt('value') must return $expectedInt for '$prompt'", expectedInt, toolArgs?.optInt("value"))
            assertEquals("optInt('value', -1) must return $expectedInt for '$prompt'", expectedInt, toolArgs?.optInt("value", -1))
            assertEquals("getInt('value_int') must return $expectedInt", expectedInt, toolArgs?.getInt("value_int"))
            assertEquals("getInt('enabled') must return $expectedInt", expectedInt, toolArgs?.getInt("enabled"))

            // String backward-compatibility getter contracts
            assertEquals("getString('value') must return '$expectedStr' for '$prompt'", expectedStr, toolArgs?.getString("value"))
            assertEquals("optString('value') must return '$expectedStr' for '$prompt'", expectedStr, toolArgs?.optString("value"))
            assertEquals("optString('value', 'def') must return '$expectedStr' for '$prompt'", expectedStr, toolArgs?.optString("value", "def"))
            assertEquals("getString('state') must return '$expectedStr'", expectedStr, toolArgs?.getString("state"))
        }
    }

    // =========================================================================
    // 2. Flashlight JSON Wire Serialization and Round-Trip Deserialization
    // =========================================================================

    @Test
    fun testFlashlight_jsonWireSerializationAndRoundTrip() {
        val onIntent = IntentDecomposerAndRouter.decompose("손전등 켜줘")
        val onArgs = onIntent.fastDecision?.toolArgs
        assertNotNull(onArgs)

        val onWireJson = onArgs!!.toString()
        assertTrue("Serialized wire JSON must contain integer 'value':1: $onWireJson", onWireJson.contains("\"value\":1"))

        // Round-trip into a brand-new standard Android JSONObject
        val parsedOn = JSONObject(onWireJson)
        assertEquals(1, parsedOn.getInt("value"))
        assertEquals(1, parsedOn.optInt("value"))
        assertEquals("on", parsedOn.getString("state"))
        assertEquals(1, parsedOn.getInt("enabled"))
        // In downstream executeControlDeviceSetting:
        val onSettingVal = parsedOn.optString("value")
        val onEnable = onSettingVal.lowercase() !in listOf("off", "false", "0", "끄기", "꺼")
        assertTrue("Parsed JSON '1' must evaluate to enable=true in device setting harness", onEnable)

        val offIntent = IntentDecomposerAndRouter.decompose("손전등 꺼줘")
        val offArgs = offIntent.fastDecision?.toolArgs
        assertNotNull(offArgs)

        val offWireJson = offArgs!!.toString()
        assertTrue("Serialized wire JSON must contain integer 'value':0: $offWireJson", offWireJson.contains("\"value\":0"))

        val parsedOff = JSONObject(offWireJson)
        assertEquals(0, parsedOff.getInt("value"))
        assertEquals(0, parsedOff.optInt("value"))
        assertEquals("off", parsedOff.getString("state"))
        assertEquals(0, parsedOff.getInt("enabled"))

        val offSettingVal = parsedOff.optString("value")
        val offEnable = offSettingVal.lowercase() !in listOf("off", "false", "0", "끄기", "꺼")
        assertFalse("Parsed JSON '0' must evaluate to enable=false in device setting harness", offEnable)
    }

    // =========================================================================
    // 3. Flashlight Phrasing Variations, Casing, Whitespace, Punctuation
    // =========================================================================

    @Test
    fun testFlashlight_phrasingVariationsAndCasing() {
        val testCases = listOf(
            "손전등 켜줘" to true,
            "손전등 켜" to true,
            "손전등켜" to true,
            "  손전등   켜줘  " to true,
            "손전등 켜줘!!!" to true,
            "손전등 ON" to true,
            "손전등 On" to true,
            "플래시 켜" to true,
            "플래시 ON" to true,
            "플래시 On" to true,
            "후레쉬 켜줘" to true,
            "라이트 켜" to true,
            "라이트 ON" to true,
            "손전등 꺼줘" to false,
            "손전등 꺼" to false,
            "손전등꺼" to false,
            "손전등 해제" to false,
            "  손전등   꺼줘   " to false,
            "손전등 꺼줄래?" to false,
            "손전등 OFF" to false,
            "손전등 Off" to false,
            "플래시 꺼" to false,
            "플래시 OFF" to false,
            "플래시 Off" to false,
            "후레쉬 꺼줘" to false,
            "후레쉬 해제" to false,
            "라이트 꺼" to false,
            "라이트 OFF" to false
        )

        for ((prompt, expectedOn) in testCases) {
            val intent = IntentDecomposerAndRouter.decompose(prompt)
            assertEquals("Prompt '$prompt' should decompose to EVERYDAY_OS_ACTION", IntentCategory.EVERYDAY_OS_ACTION, intent.primaryCategory)
            val expectedVal = if (expectedOn) 1 else 0
            assertEquals("Prompt '$prompt' should have value $expectedVal", expectedVal, intent.fastDecision?.toolArgs?.getInt("value"))
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
                        assertEquals(IntentCategory.EVERYDAY_OS_ACTION, intent.primaryCategory)
                        assertTrue(invokeIsBgGoal(prompt))
                    } else {
                        val fgPrompt = "카카오톡 열어서 팀 단톡방에 메시지 보내줘"
                        val intent = IntentDecomposerAndRouter.decompose(fgPrompt)
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
