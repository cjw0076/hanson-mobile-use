package com.hanson.mobileuse

import android.content.Context
import android.webkit.ConsoleMessage
import com.hanson.mobileuse.ui.components.CanvasErrorTelemetry
import com.hanson.mobileuse.ui.components.GenerativeUiRendererDefaults
import com.hanson.mobileuse.ui.components.GenerativeUiSpec
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import com.hanson.mobileuse.ui.components.HansonJsBridge
import com.hanson.mobileuse.ui.components.HansonWebChromeClient
import com.hanson.mobileuse.ui.components.HansonWebViewClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive E2E / Integration Test Suite for Milestone 2 & Features 10-15, 17:
 * - Feature 10: Live Code Canvas Generation (`draw_with_code`)
 * - Feature 11: WebView Generative UI Container Lifecycle
 * - Feature 12: Interactive Canvas Bridge (`window.HansonOS`)
 * - Feature 13: Console & Error Interception (`onConsoleMessage`, `onReceivedError`)
 * - Feature 14: JS Runtime Error Handler (`reportError`, `window.onerror`)
 * - Feature 15: Agent Closed-Loop Code Canvas Self-Correction Feedback Telemetry
 * - Feature 17: Defensive Grayscale Filter on Dynamic Web Containers
 */
class CodeCanvasErrorSelfCorrectionTest {

    private lateinit var dummyContext: Context
    private var capturedCommand: String? = null
    private var capturedError: String? = null
    private lateinit var bridge: HansonJsBridge

    private class TestConsoleMessage(
        private val msg: String,
        private val src: String,
        private val line: Int,
        private val lvl: ConsoleMessage.MessageLevel
    ) : ConsoleMessage(msg, src, line, lvl) {
        override fun messageLevel(): ConsoleMessage.MessageLevel = lvl
        override fun message(): String = msg
        override fun sourceId(): String = src
        override fun lineNumber(): Int = line
    }

    @Before
    fun setUp() {
        GenerativeUiStateHolder.clearError()
        capturedCommand = null
        capturedError = null

        dummyContext = object : android.content.ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
        }

        bridge = HansonJsBridge(
            context = dummyContext,
            onRunCommand = { cmd -> capturedCommand = cmd },
            onError = { err -> capturedError = err }
        )
    }

    @After
    fun tearDown() {
        GenerativeUiStateHolder.clearError()
    }

    // =========================================================================
    // Tier 1: CanvasErrorTelemetry Serialization & Schema (Feature 14)
    // =========================================================================

    @Test
    fun testCanvasErrorTelemetry_serializesAndDeserializesStructuredFields() {
        val original = CanvasErrorTelemetry(
            type = "runtime_error",
            message = "Uncaught TypeError: Cannot read properties of undefined (reading 'getContext')",
            source = "https://hanson.local/canvas.js",
            lineNumber = 42,
            columnNumber = 17,
            timestamp = 1731075000000L
        )

        val jsonStr = original.toJsonString()
        assertTrue(jsonStr.contains("runtime_error"))
        assertTrue(jsonStr.contains("getContext"))
        assertTrue(jsonStr.contains("42"))

        val restored = CanvasErrorTelemetry.fromJson(jsonStr)
        assertEquals("runtime_error", restored.type)
        assertEquals("Uncaught TypeError: Cannot read properties of undefined (reading 'getContext')", restored.message)
        assertEquals("https://hanson.local/canvas.js", restored.source)
        assertEquals(42, restored.lineNumber)
        assertEquals(17, restored.columnNumber)
        assertEquals(1731075000000L, restored.timestamp)
    }

    @Test
    fun testCanvasErrorTelemetry_handlesAlternativeJsonFieldNames() {
        // e.g. "line" instead of "lineNumber", "col" instead of "columnNumber", "reason" instead of "message"
        val altJson = """
            {
                "type": "unhandled_rejection",
                "reason": "Promise rejected with NetworkError",
                "line": 99,
                "col": 5
            }
        """.trimIndent()

        val parsed = CanvasErrorTelemetry.fromJson(altJson)
        assertEquals("unhandled_rejection", parsed.type)
        assertEquals("Promise rejected with NetworkError", parsed.message)
        assertEquals(99, parsed.lineNumber)
        assertEquals(5, parsed.columnNumber)
    }

    @Test
    fun testCanvasErrorTelemetry_toFeedbackPrompt_generatesActionablePrompt() {
        val telemetry = CanvasErrorTelemetry(
            type = "syntax_error",
            message = "Unexpected token '<'",
            source = "mini_app.html",
            lineNumber = 15,
            columnNumber = 3
        )

        val prompt = telemetry.toFeedbackPrompt()
        assertTrue(prompt.contains("[Canvas Error Intercepted]"))
        assertTrue(prompt.contains("syntax_error"))
        assertTrue(prompt.contains("Unexpected token '<'"))
        assertTrue(prompt.contains("라인 15"))
        assertTrue(prompt.contains("컬럼 3"))
        assertTrue(prompt.contains("Self-Correction"))
    }

    // =========================================================================
    // Tier 1: HTML Instrumentation (Defensive Achromatic CSS & Error Handlers) (Features 13, 14, 17)
    // =========================================================================

    @Test
    fun testGenerativeUiRendererDefaults_instrumentsHtmlWithHeadTag() {
        val rawHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Test Mini App</title>
            </head>
            <body>
                <canvas id="c"></canvas>
            </body>
            </html>
        """.trimIndent()

        val instrumented = GenerativeUiRendererDefaults.instrumentHtml(rawHtml)

        // Verifies defensive grayscale CSS injection
        assertTrue("Must inject defensive grayscale CSS: $instrumented", instrumented.contains("filter: grayscale(100%) !important"))
        assertTrue(instrumented.contains("-webkit-filter: grayscale(100%) !important"))

        // Verifies error handlers injection
        assertTrue("Must inject window.onerror: $instrumented", instrumented.contains("window.onerror"))
        assertTrue("Must inject unhandledrejection: $instrumented", instrumented.contains("unhandledrejection"))
        assertTrue("Must wire to window.HansonOS.reportError: $instrumented", instrumented.contains("window.HansonOS.reportError"))
    }

    @Test
    fun testGenerativeUiRendererDefaults_instrumentsHtmlWithoutHeadTag() {
        val fragment = """
            <div style="background: red; color: blue;">
                <h1>Colorful Unchecked Widget</h1>
            </div>
        """.trimIndent()

        val instrumented = GenerativeUiRendererDefaults.instrumentHtml(fragment)

        // Must still inject grayscale filter and error script even without <head> or <html>
        assertTrue(instrumented.contains("filter: grayscale(100%) !important"))
        assertTrue(instrumented.contains("window.onerror"))
        assertTrue(instrumented.contains("Colorful Unchecked Widget"))
    }

    @Test
    fun testGenerativeUiRendererDefaults_instrumentationIsIdempotent() {
        val raw = """
            <html>
            <head>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val firstPass = GenerativeUiRendererDefaults.instrumentHtml(raw)
        val secondPass = GenerativeUiRendererDefaults.instrumentHtml(firstPass)

        // Counting occurrences of achromatic-guard style
        val count = Regex("<style id=\"achromatic-guard\">").findAll(secondPass).count()
        assertEquals("Should not duplicate achromatic style on repeated instrumentation", 1, count)
    }

    // =========================================================================
    // Tier 1: JS Bridge Error Telemetry Dispatch (Feature 12 & 14)
    // =========================================================================

    @Test
    fun testHansonJsBridge_reportError_updatesStateHolderAndInvokesCallback() {
        val errPayload = JSONObject().apply {
            put("type", "runtime_error")
            put("message", "Script evaluation failed at renderChart()")
            put("line", 55)
        }.toString()

        bridge.reportError(errPayload)

        // Callback invoked
        assertEquals(errPayload, capturedError)

        // StateHolder updated
        val lastErr = GenerativeUiStateHolder.lastError.value
        assertNotNull("GenerativeUiStateHolder must capture error", lastErr)
        assertEquals("runtime_error", lastErr?.type)
        assertEquals("Script evaluation failed at renderChart()", lastErr?.message)
        assertEquals(55, lastErr?.lineNumber)
    }

    @Test
    fun testHansonJsBridge_runCommand_executesCallback() {
        bridge.runCommand("원터치 배달 주문 상태 확인해줘")
        assertEquals("원터치 배달 주문 상태 확인해줘", capturedCommand)
    }

    @Test
    fun testHansonJsBridge_reportError_ignoresBlankInput() {
        bridge.reportError("   ")
        assertNull("Blank error payload must be ignored", capturedError)
        assertNull("StateHolder must remain null", GenerativeUiStateHolder.lastError.value)
    }

    // =========================================================================
    // Tier 1: WebChromeClient & WebViewClient Interception (Feature 13)
    // =========================================================================

    @Test
    fun testHansonWebChromeClient_interceptsErrorMessageAndDispatchesToBridge() {
        val chromeClient = HansonWebChromeClient(bridge)

        // Simulating a console error message
        val consoleMessage = TestConsoleMessage(
            "Uncaught ReferenceError: Chart is not defined",
            "canvas_app.html",
            88,
            ConsoleMessage.MessageLevel.ERROR
        )

        val handled = chromeClient.onConsoleMessage(consoleMessage)
        assertNotNull("Error must be captured by bridge", capturedError)
        val json = JSONObject(capturedError!!)
        assertEquals("console_error", json.optString("type"))
        assertEquals("Uncaught ReferenceError: Chart is not defined", json.optString("message"))
        assertEquals("canvas_app.html", json.optString("source"))
        assertEquals(88, json.optInt("lineNumber"))
    }

    @Test
    fun testHansonWebChromeClient_ignoresNonErrorConsoleMessages() {
        val chromeClient = HansonWebChromeClient(bridge)

        val logMessage = TestConsoleMessage(
            "Chart rendering initialized successfully",
            "canvas_app.html",
            10,
            ConsoleMessage.MessageLevel.LOG
        )

        chromeClient.onConsoleMessage(logMessage)
        assertNull("Log level messages should not trigger error telemetry", capturedError)

        val debugMessage = TestConsoleMessage(
            "Debugging tick 12",
            "canvas_app.html",
            12,
            ConsoleMessage.MessageLevel.DEBUG
        )

        chromeClient.onConsoleMessage(debugMessage)
        assertNull("Debug level messages should not trigger error telemetry", capturedError)
    }

    // =========================================================================
    // Tier 1: GenerativeUiStateHolder Lifecycle & Error State Reset (Feature 11 & 15)
    // =========================================================================

    @Test
    fun testGenerativeUiStateHolder_clearErrorOnPublishSpec() {
        GenerativeUiStateHolder.reportError("{\"type\": \"error_test\", \"message\": \"initial error\"}")
        assertNotNull(GenerativeUiStateHolder.lastError.value)

        // Publishing a new spec clears existing error state for fresh run
        val newSpec = GenerativeUiSpec(
            title = "새 차트",
            subtitle = "수정된 캔버스",
            htmlContent = "<div>Clean HTML</div>"
        )
        GenerativeUiStateHolder.publishSpec(newSpec)

        assertNull("Publishing new spec must clear error state", GenerativeUiStateHolder.lastError.value)
        assertEquals("새 차트", GenerativeUiStateHolder.activeSpec.value?.title)
    }

    @Test
    fun testGenerativeUiStateHolder_restoreDefaultCockpitClearsError() {
        GenerativeUiStateHolder.reportError("{\"type\": \"error_test\", \"message\": \"fatal crash\"}")
        assertNotNull(GenerativeUiStateHolder.lastError.value)

        GenerativeUiStateHolder.restoreDefaultCockpit()
        assertNull("Restoring default cockpit must clear error state", GenerativeUiStateHolder.lastError.value)
        assertEquals("원터치 마이크로 보드", GenerativeUiStateHolder.activeSpec.value?.title)
    }

    // =========================================================================
    // Tier 2: Boundary & Corner Cases (Malformed Payloads, Bursts, Edge HTML)
    // =========================================================================

    @Test
    fun testCanvasErrorTelemetry_handlesMalformedOrNonJsonStringsGracefully() {
        val rawNonJson = "Uncaught SyntaxError at unknown location (line 1)"
        val telemetry = CanvasErrorTelemetry.fromJson(rawNonJson)

        assertEquals("raw_error", telemetry.type)
        assertEquals(rawNonJson, telemetry.message)
        assertEquals(rawNonJson, telemetry.rawJson)
    }

    @Test
    fun testCanvasErrorTelemetry_handlesLargePayloadWithoutCrashing() {
        val largeMessage = "A".repeat(15_000)
        val json = JSONObject().apply {
            put("type", "buffer_overflow")
            put("message", largeMessage)
        }.toString()

        val parsed = CanvasErrorTelemetry.fromJson(json)
        assertEquals("buffer_overflow", parsed.type)
        assertEquals(15_000, parsed.message.length)
        val prompt = parsed.toFeedbackPrompt()
        assertTrue(prompt.contains("[Canvas Error Intercepted]"))
    }

    @Test
    fun testGenerativeUiRendererDefaults_instrumentsEmptyStringSafely() {
        val result = GenerativeUiRendererDefaults.instrumentHtml("")
        assertTrue(result.contains("filter: grayscale(100%) !important"))
        assertTrue(result.contains("window.onerror"))
    }

    @Test
    fun testGenerativeUiStateHolder_dismissHidesActiveSpec() {
        GenerativeUiStateHolder.dismiss()
        assertNull(GenerativeUiStateHolder.activeSpec.value)
    }

    // =========================================================================
    // Tier 3 & 4: Closed-Loop Self-Correction Feedback Simulation
    // =========================================================================

    @Test
    fun testClosedLoopSelfCorrection_interceptionFeedbackTriggersNextTurn() {
        // Step 1: Agent creates a canvas with a bug (e.g. missing math import)
        val buggyCode = """
            <!DOCTYPE html>
            <html>
            <head><title>Buggy Chart</title></head>
            <body>
            <script>
                // Bug: calculateTrajectory is undefined
                calculateTrajectory();
            </script>
            </body>
            </html>
        """.trimIndent()

        val instrumentedHtml = GenerativeUiRendererDefaults.instrumentHtml(buggyCode)
        GenerativeUiStateHolder.publishSpec(
            GenerativeUiSpec(
                id = "spec_buggy",
                title = "시각화 차트",
                subtitle = "궤적 계산 중",
                htmlContent = instrumentedHtml
            )
        )

        // Step 2: Runtime executes in WebView; error intercepted by window.onerror / ChromeClient
        val chromeClient = HansonWebChromeClient(bridge)
        chromeClient.onConsoleMessage(
            TestConsoleMessage(
                "Uncaught ReferenceError: calculateTrajectory is not defined",
                "hanson://local/canvas.html",
                25,
                ConsoleMessage.MessageLevel.ERROR
            )
        )

        // Step 3: Telemetry captured in StateHolder
        val error = GenerativeUiStateHolder.lastError.value
        assertNotNull("Runtime error must be captured", error)

        // Step 4: Feedback prompt generated for Agent Self-Correction
        val feedbackPrompt = error!!.toFeedbackPrompt()
        assertTrue("Feedback prompt must contain error details: $feedbackPrompt", feedbackPrompt.contains("calculateTrajectory"))
        assertTrue("Feedback prompt must instruct self-correction", feedbackPrompt.contains("Self-Correction"))

        // Step 5: Agent receives prompt, produces corrected code, and re-publishes
        val correctedCode = """
            <!DOCTYPE html>
            <html>
            <head><title>Corrected Chart</title></head>
            <body>
            <script>
                function calculateTrajectory() { return [1, 2, 3]; }
                calculateTrajectory();
            </script>
            </body>
            </html>
        """.trimIndent()

        GenerativeUiStateHolder.publishSpec(
            GenerativeUiSpec(
                id = "spec_fixed",
                title = "시각화 차트 (수정 완료)",
                subtitle = "정상 렌더링",
                htmlContent = GenerativeUiRendererDefaults.instrumentHtml(correctedCode)
            )
        )

        // Step 6: Verify clean state restored
        assertNull("Self-correction re-publish must clear error", GenerativeUiStateHolder.lastError.value)
        assertEquals("시각화 차트 (수정 완료)", GenerativeUiStateHolder.activeSpec.value?.title)
    }

    @Test
    fun testAhaboardMultimodalMediaBlocks_roundTripAndHumanSummaryEnrichment() {
        for (fmt in com.hanson.mobileuse.agent.MediaFormatType.entries) {
            val block = com.hanson.mobileuse.agent.MultimodalMediaBlock(
                format = fmt,
                title = "테스트 미디어 · ${fmt.name}",
                subtitle = fmt.labelKo,
                mediaUrlOrUri = "https://hanson.local/sample.${fmt.name.lowercase()}",
                inlineHtmlContent = "<div>${fmt.badge}</div>",
                recommendedHeightDp = 235
            )
            val json = block.toJson()
            val restored = com.hanson.mobileuse.agent.MultimodalMediaBlock.fromJson(json)
            assertNotNull(restored)
            assertEquals(fmt, restored!!.format)
            assertEquals(block.title, restored.title)

            val rawMsg = com.hanson.mobileuse.agent.LiveChatMessage(
                sender = com.hanson.mobileuse.agent.ChatSender.AGENT,
                text = "원본 출력",
                toolBadge = "NEXUS-${fmt.name}",
                mediaBlock = restored
            )
            val enriched = com.hanson.mobileuse.agent.HumanSummaryAndCardBuilder.enrichMessageForHumanDisplay(rawMsg)
            assertNotNull(enriched.mediaBlock)
            assertEquals(fmt, enriched.mediaBlock!!.format)
            assertNotNull(enriched.detailPayload)
        }
    }
}
