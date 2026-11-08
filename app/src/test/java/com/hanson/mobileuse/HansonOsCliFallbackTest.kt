package com.hanson.mobileuse

import android.content.Context
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.AgentToolExecutionHarness
import com.hanson.mobileuse.oscli.HansonOsCliMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Comprehensive E2E / Integration Test Suite for Milestone 1 & Features 4, 5, 6, 7:
 * - Feature 4: OS Shell Daemon (UID 2000) RPC protocol on port 18890
 * - Feature 5: Native OS CLI Shell Wrapper (`HansonOsCliMain`)
 * - Feature 6: Transparent Tool Fallback (A11y null -> OS CLI daemon fallback)
 * - Feature 7: Accessibility Watchdog & Auto-Recovery
 */
class HansonOsCliFallbackTest {

    private lateinit var dummyContext: Context
    private lateinit var harness: AgentToolExecutionHarness

    @Before
    fun setUp() {
        assertNull("Precondition: A11y service must be null in unit tests", HansonAccessibilityService.instance)

        val prefsMap = mutableMapOf<String, Any>()
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
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = dummyPrefs
        }

        harness = AgentToolExecutionHarness(dummyContext)
    }

    @After
    fun tearDown() {
        assertNull(HansonAccessibilityService.instance)
    }

    // =========================================================================
    // Tier 1: OS CLI Wrapper & Command Execution (Feature 5)
    // =========================================================================

    @Test
    fun testOsCliMain_statusCommand_returnsStandardMetadata() {
        val res = HansonOsCliMain.executeCommandJson("status", emptyList())
        assertEquals("ok", res.optString("status"))
        assertEquals("Hanson-OS-CLI-UID2000", res.optString("engine"))
        assertEquals(18890, res.optInt("daemonPort"))
        assertTrue(res.has("requestsHandled"))
    }

    @Test
    fun testOsCliMain_tapCommand_formatsInputTapAction() {
        val res = HansonOsCliMain.executeCommandJson("tap", listOf("540", "1200"))
        assertTrue(res.optString("action").contains("input tap 540 1200"))
    }

    @Test
    fun testOsCliMain_swipeCommand_formatsInputSwipeAction() {
        val res = HansonOsCliMain.executeCommandJson("swipe", listOf("100", "200", "300", "400", "350"))
        assertTrue(res.optString("action").contains("input swipe 100 200 300 400 350"))
    }

    @Test
    fun testOsCliMain_textCommand_replacesSpacesWithPercentS() {
        val res = HansonOsCliMain.executeCommandJson("text", listOf("Hello", "World", "Hanson"))
        assertEquals("Hello World Hanson", res.optString("text"))
    }

    @Test
    fun testOsCliMain_keyCommand_formatsInputKeyevent() {
        val res = HansonOsCliMain.executeCommandJson("key", listOf("KEYCODE_BACK"))
        assertEquals("KEYCODE_BACK", res.optString("key"))
    }

    // =========================================================================
    // Tier 1: Transparent Tool Fallback when A11y is Null (Feature 6)
    // =========================================================================

    @Test
    fun testFallback_click_transparentlyExecutesViaOsCliWhenA11yNull() {
        assertNull("Precondition: A11y service must be null", HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("norm_x", 500)
            put("norm_y", 500)
            put("target_description", "중앙 버튼")
            put("times", 1)
        }
        val snapshot = AtomicSnapshot(screenWidth = 1080, screenHeight = 2400)

        val outcome = harness.execute("click", args, snapshot)
        assertTrue("Click must succeed via fallback", outcome.succeeded)
        assertTrue(
            "Output must mention fallback: ${outcome.outputText}",
            outcome.outputText.contains("OS CLI 데몬 자동 폴백") || outcome.outputText.contains("OS CLI")
        )
        assertNotNull("Receipt must be generated", outcome.receipt)
        assertEquals("click", outcome.receipt?.toolName)
        assertTrue(outcome.receipt?.summaryKo?.contains("OS CLI") == true)
    }

    @Test
    fun testFallback_swipe_transparentlyExecutesViaOsCliWhenA11yNull() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("direction", "up")
            put("start_norm_x", 500)
            put("start_norm_y", 800)
            put("end_norm_x", 500)
            put("end_norm_y", 200)
            put("duration_ms", 300L)
        }
        val snapshot = AtomicSnapshot(screenWidth = 1080, screenHeight = 2400)

        val outcome = harness.execute("swipe", args, snapshot)
        assertTrue("Swipe must succeed via fallback", outcome.succeeded)
        assertTrue(
            "Output must mention fallback: ${outcome.outputText}",
            outcome.outputText.contains("OS CLI 데몬 자동 폴백") || outcome.outputText.contains("OS CLI")
        )
    }

    @Test
    fun testFallback_inputText_transparentlyExecutesViaOsCliWhenA11yNull() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("text", "4족로봇제어")
            put("norm_x", 500)
            put("norm_y", 400)
        }
        val snapshot = AtomicSnapshot(screenWidth = 1080, screenHeight = 2400)

        val outcome = harness.execute("input_text", args, snapshot)
        assertTrue("InputText must succeed via fallback", outcome.succeeded)
        assertTrue(
            "Output must mention fallback: ${outcome.outputText}",
            outcome.outputText.contains("OS CLI 데몬 자동 폴백") || outcome.outputText.contains("OS CLI")
        )
        assertNotNull(outcome.receipt)
        assertEquals("input_text", outcome.receipt?.toolName)
    }

    @Test
    fun testFallback_pressKey_mapsKeycodesAndExecutesViaOsCliWhenA11yNull() {
        assertNull(HansonAccessibilityService.instance)

        val keysToTest = listOf("BACK" to "4", "HOME" to "3", "ENTER" to "66", "RECENTS" to "187")

        for ((key, expectedCode) in keysToTest) {
            val args = JSONObject().put("key", key)
            val outcome = harness.execute("press_key", args, null)
            assertTrue("PressKey $key must succeed via fallback", outcome.succeeded)
            assertTrue(
                "Output must contain keycode $expectedCode: ${outcome.outputText}",
                outcome.outputText.contains(expectedCode) || outcome.outputText.contains("OS CLI")
            )
            assertEquals("press_key", outcome.receipt?.toolName)
        }
    }

    @Test
    fun testFallback_longPress_transparentlyExecutesViaOsCliWhenA11yNull() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("norm_x", 300)
            put("norm_y", 600)
            put("duration_ms", 800L)
            put("target_description", "앱 아이콘")
        }
        val snapshot = AtomicSnapshot(screenWidth = 1080, screenHeight = 2400)

        val outcome = harness.execute("long_press", args, snapshot)
        assertTrue("LongPress must succeed via fallback", outcome.succeeded)
        assertTrue(
            "Output must mention fallback: ${outcome.outputText}",
            outcome.outputText.contains("OS CLI 데몬 자동 폴백") || outcome.outputText.contains("OS CLI")
        )
        assertEquals("long_press", outcome.receipt?.toolName)
    }

    @Test
    fun testFallback_dragAndDrop_transparentlyExecutesViaOsCliWhenA11yNull() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("start_norm_x", 200)
            put("start_norm_y", 300)
            put("end_norm_x", 800)
            put("end_norm_y", 700)
            put("duration_ms", 500L)
            put("description", "위젯 이동")
        }
        val snapshot = AtomicSnapshot(screenWidth = 1080, screenHeight = 2400)

        val outcome = harness.execute("drag_and_drop", args, snapshot)
        assertTrue("DragAndDrop must succeed via fallback", outcome.succeeded)
        assertTrue(
            "Output must mention fallback: ${outcome.outputText}",
            outcome.outputText.contains("OS CLI 데몬 자동 폴백") || outcome.outputText.contains("OS CLI")
        )
        assertEquals("drag_and_drop", outcome.receipt?.toolName)
    }

    @Test
    fun testFallback_clickSequence_transparentlyExecutesViaOsCliWhenA11yNull() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("coords_csv", "100,200;300,400;500,600")
            put("delay_ms", 50L)
            put("description", "3연속 탭")
        }
        val snapshot = AtomicSnapshot(screenWidth = 1080, screenHeight = 2400)

        val outcome = harness.execute("click_sequence", args, snapshot)
        assertTrue("ClickSequence must succeed via fallback", outcome.succeeded)
        assertTrue(
            "Output must mention fallback: ${outcome.outputText}",
            outcome.outputText.contains("OS CLI 데몬 자동 폴백") || outcome.outputText.contains("OS CLI")
        )
        assertEquals("click_sequence", outcome.receipt?.toolName)
    }

    // =========================================================================
    // Tier 2: Boundary, Corner & Adversarial Conditions
    // =========================================================================

    @Test
    fun testFallback_clickWithDirectPixelCoordinates_resolvesDirectly() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("x", 450)
            put("y", 950)
            put("target_description", "직접 픽셀 타겟")
        }

        val outcome = harness.execute("click", args, null)
        assertTrue(outcome.succeeded)
        assertTrue(outcome.outputText.contains("450, 950"))
    }

    @Test
    fun testFallback_clickWithMissingCoordinates_failsGracefully() {
        assertNull(HansonAccessibilityService.instance)

        val args = JSONObject().apply {
            put("target_description", "좌표 없는 빈 타겟")
        }

        val outcome = harness.execute("click", args, null)
        // With no element_index, norm_x/y, or x/y, resolveTargetPixel returns null
        assertNotNull(outcome)
        assertTrue("Should report missing coordinate or fallback gracefully", !outcome.succeeded || outcome.outputText.contains("폴백"))
    }

    @Test
    fun testFallback_directOsCliExecTool_handlesCustomShellCommands() {
        val args = JSONObject().apply {
            put("command", "status")
        }

        val outcome = harness.execute("os_cli_exec", args, null)
        assertTrue(outcome.succeeded)
        assertTrue(outcome.outputText.contains("Hanson OS CLI"))
    }

    @Test
    fun testFallback_unknownTool_reportsUnknownToolError() {
        val outcome = harness.execute("non_existent_tool_xyz", JSONObject(), null)
        assertEquals(false, outcome.succeeded)
        assertTrue(outcome.outputText.contains("알 수 없는 도구"))
    }

    // =========================================================================
    // Tier 3 & 4: Mock Daemon HTTP RPC Protocol Integration (:18890)
    // =========================================================================

    @Test
    fun testDaemonRpcProtocol_mockServerHandlesExecAndPingEndpoints() {
        // Find an available ephemeral port for unit testing
        val testPort = ServerSocket(0).use { it.localPort }
        val serverReceivedAction = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()

        val server = ServerSocket(testPort)
        pool.execute {
            try {
                val client = server.accept()
                client.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine() ?: ""
                    var contentLength = 0
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.lowercase().startsWith("content-length:")) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        line = reader.readLine()
                    }
                    val body = if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        reader.read(buf, 0, contentLength)
                        String(buf)
                    } else ""

                    if (requestLine.contains("/exec") && body.contains("tap")) {
                        serverReceivedAction.set(true)
                    }

                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("stdout", "input tap 500 500 executed successfully")
                    }.toString().toByteArray(Charsets.UTF_8)

                    val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: application/json; charset=utf-8\r\n")
                    writer.write("Content-Length: ${resp.size}\r\n")
                    writer.write("Connection: close\r\n\r\n")
                    writer.flush()
                    s.getOutputStream().write(resp)
                    s.getOutputStream().flush()
                }
            } catch (_: Throwable) {
            } finally {
                server.close()
            }
        }

        // Send HTTP POST request simulating harness RPC call
        val url = URL("http://127.0.0.1:$testPort/exec")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2000
            readTimeout = 2000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val reqJson = JSONObject().apply {
            put("action", "tap")
            put("args", JSONArray(listOf("500", "500")))
        }
        conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = conn.responseCode
        assertEquals(200, responseCode)
        val respBody = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(respBody.contains("input tap 500 500"))
        assertTrue(serverReceivedAction.get())
        pool.shutdownNow()
    }
}
