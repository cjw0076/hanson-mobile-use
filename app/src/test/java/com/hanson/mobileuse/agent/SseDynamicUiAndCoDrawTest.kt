package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SseDynamicUiAndCoDrawTest {

    @Before
    fun setUp() {
        DynamicUiEventBus.reset()
        CoDrawingCanvasEngine.resetForNewSession()
    }

    @After
    fun tearDown() {
        DynamicUiEventBus.reset()
        CoDrawingCanvasEngine.resetForNewSession()
    }

    @Test
    fun testSseLineParser_handlesMultiLineDataIdsCommentsAndRetries() {
        val parser = SseLineParser()
        assertNull(parser.feedLine(": keepalive ping"))
        assertNull(parser.feedLine("id: 42"))
        assertNull(parser.feedLine("event: tutor_command"))
        assertNull(parser.feedLine("retry: 2500"))
        assertNull(parser.feedLine("""data: {"part":1,"""))
        assertNull(parser.feedLine("""data: "ok":true}"""))

        val frame = parser.feedLine("")
        assertNotNull(frame)
        assertEquals("42", frame!!.id)
        assertEquals("tutor_command", frame.event)
        assertEquals(2500L, frame.retryMs)
        assertEquals("{\"part\":1,\n\"ok\":true}", frame.data)

        // EOF flush without trailing empty line
        assertNull(parser.feedLine("event: stream_complete"))
        assertNull(parser.feedLine("""data: {"done":true}"""))
        val eofFrame = parser.flushPendingFrame()
        assertNotNull(eofFrame)
        assertEquals("stream_complete", eofFrame!!.event)
        assertEquals("""{"done":true}""", eofFrame.data)
    }

    @Test
    fun testIncrementalBoardStreamCompiler_streamsSpeakBoardQuizCheckAndCoDrawWithoutLeakingDirectives() {
        val raw = """
            SPEAK: 오른쪽 상단 설정 버튼을 누르고 알림 항목을 켜면 바로 완료됩니다.
            BOARD:
            카카오톡 알림 설정 가이드
            • 1단계: 우측 상단 톱니바퀴 아이콘 선택
            • 2단계: 알림 허용 스위치 켜기
            QUIZ: 알림을 켜기 위해 먼저 눌러야 하는 위치는? | 우측 상단 톱니바퀴 | 하단 채팅 탭 | 좌측 메뉴 | 0
            CHECK: 설정 아이콘 탭 완료
            CHECK: 알림 스위치 ON 확인
            CODRAW: RING 0.88 0.12 0.08 설정 버튼
            CODRAW: ARROW 0.50 0.30 0.88 0.12 여기 클릭
        """.trimIndent()

        val compiled = IncrementalBoardStreamCompiler.compileIncremental(raw, turnIndex = 5)

        assertTrue(compiled.script.isRichBoard)
        assertFalse("Visual body must not leak SPEAK:", compiled.script.visualBodyText.contains("SPEAK:"))
        assertFalse("Visual body must not leak QUIZ:", compiled.script.visualBodyText.contains("QUIZ:"))
        assertFalse("Visual body must not leak CHECK:", compiled.script.visualBodyText.contains("CHECK:"))
        assertFalse("Visual body must not leak CODRAW:", compiled.script.visualBodyText.contains("CODRAW:"))
        assertFalse("Spoken prose must never contain asterisks", compiled.script.combinedSpokenText.contains("*"))
        assertEquals("오른쪽 상단 설정 버튼을 누르고 알림 항목을 켜면 바로 완료됩니다.", compiled.explicitSpeak)

        val quizzes = compiled.widgets.filterIsInstance<DynamicUiWidget.QuizCard>()
        val checks = compiled.widgets.filterIsInstance<DynamicUiWidget.ChecklistItem>()
        assertEquals(1, quizzes.size)
        assertEquals(3, quizzes[0].options.size)
        assertEquals(0, quizzes[0].correctIndex)
        assertEquals(2, checks.size)

        assertEquals(2, compiled.coDrawElements.size)
        assertEquals(CoDrawToolKind.SPOTLIGHT_RING, compiled.coDrawElements[0].kind)
        assertEquals("설정 버튼", compiled.coDrawElements[0].label)
        assertEquals("1", compiled.coDrawElements[0].stepBadge)
        assertEquals(CoDrawToolKind.ARROW, compiled.coDrawElements[1].kind)
        assertEquals("여기 클릭", compiled.coDrawElements[1].label)
    }

    @Test
    fun testDynamicUiEventBus_lastEventIdReplayAndExternalJsonIngestion() {
        DynamicUiEventBus.beginStream(sourceLabel = "GEMINI-SSE", spaceId = "live_test", turnIndex = 1)
        val seqAfterStart = DynamicUiEventBus.surfaceState.value.lastSequenceId
        assertTrue(seqAfterStart > 0L)
        assertEquals(SseStreamPhase.STREAMING, DynamicUiEventBus.surfaceState.value.phase)

        DynamicUiEventBus.appendStreamingDelta("SPEAK: 테스트 스트림이 시작됐어요.\n")
        DynamicUiEventBus.appendStreamingDelta("BOARD:\n실시간 테스트 헤드라인\n• 핵심 수치: $83.16\n")
        DynamicUiEventBus.appendStreamingDelta("CODRAW: RING 0.50 0.50 0.10 중앙 강조\n")

        assertEquals(1, CoDrawingCanvasEngine.state.value.aiElementCount)
        assertTrue(DynamicUiEventBus.surfaceState.value.events.isNotEmpty())

        // Ingest an external tutor_command & interactive Quiz widget over JSON (e.g. POST :18888/board/events)
        val quizJson = JSONObject()
            .put("name", "ui_widget")
            .put("widgetType", "QUIZ")
            .put("id", "quiz_ext_1")
            .put("question", "SSE 재연결 헤더 이름은?")
            .put("options", JSONArray().put("Last-Event-ID").put("X-Retry-Only"))
            .put("correctIndex", 0)
        assertTrue(DynamicUiEventBus.ingestExternalJsonEvent("ui_widget", quizJson))

        DynamicUiEventBus.selectQuizOption("quiz_ext_1", 0)
        val selectedQuiz = DynamicUiEventBus.surfaceState.value.widgets
            .filterIsInstance<DynamicUiWidget.QuizCard>()
            .first { it.id == "quiz_ext_1" }
        assertEquals(0, selectedQuiz.selectedIndex)

        // Verify replay since `seqAfterStart` produces valid RFC 8895 SSE wire frames
        val replayed = DynamicUiEventBus.replayEnvelopesSince(seqAfterStart)
        assertTrue("Expected replayed SSE frames after seq $seqAfterStart", replayed.size >= 4)
        val wire = replayed.first().toSseWireFrame()
        assertTrue(wire.startsWith("id: "))
        assertTrue(wire.contains("\nevent: "))
        assertTrue(wire.contains("\ndata: "))
        assertTrue(wire.endsWith("\n\n"))
    }

    @Test
    fun testCoDrawingCanvasEngine_humanStrokesUndoRedoLayersAndSpatialSummary() {
        CoDrawingCanvasEngine.selectTool(CoDrawToolKind.CIRCLE_LASSO)
        CoDrawingCanvasEngine.beginHumanStroke(0.20f, 0.30f)
        CoDrawingCanvasEngine.appendHumanStrokePoint(0.60f, 0.30f)
        CoDrawingCanvasEngine.appendHumanStrokePoint(0.60f, 0.50f)
        val committed = CoDrawingCanvasEngine.finishHumanStroke()

        assertNotNull(committed)
        assertEquals(1, CoDrawingCanvasEngine.state.value.humanElementCount)
        assertTrue(CoDrawingCanvasEngine.state.value.canUndo)

        // Parse 0..1000 Set-of-Marks scale CODRAW directive cleanly into 0..1 normalized space
        val aiArrow = CoDrawingCanvasEngine.parseCoDrawDirectiveLine("CODRAW: ARROW 250 250 750 600 여기 이동", stepNumber = 2)
        assertNotNull(aiArrow)
        assertEquals(0.25f, aiArrow!!.points[0].nx, 0.001f)
        assertEquals(0.75f, aiArrow.points[1].nx, 0.001f)
        CoDrawingCanvasEngine.addAiElement(aiArrow)
        assertEquals(1, CoDrawingCanvasEngine.state.value.aiElementCount)

        // Toggle AI layer visibility without deleting elements
        CoDrawingCanvasEngine.toggleLayerVisibility(CoDrawAuthor.AI)
        assertEquals(1, CoDrawingCanvasEngine.state.value.visibleElements.size)
        assertEquals(CoDrawAuthor.HUMAN, CoDrawingCanvasEngine.state.value.visibleElements.first().author)
        CoDrawingCanvasEngine.toggleLayerVisibility(CoDrawAuthor.AI)
        assertEquals(2, CoDrawingCanvasEngine.state.value.visibleElements.size)

        // Spatial prompt summary accurately reflects normalized 0..1000 bounding region
        val summary = CoDrawingCanvasEngine.buildSpatialPromptSummary(foregroundAppLabel = "KakaoTalk")
        assertTrue(summary.contains("KakaoTalk"))
        assertTrue(summary.contains("x=200..600"))
        assertTrue(summary.contains("y=300..500"))

        // Undo / Redo round-trip
        assertTrue(CoDrawingCanvasEngine.undo())
        assertEquals(1, CoDrawingCanvasEngine.state.value.elements.size)
        assertTrue(CoDrawingCanvasEngine.state.value.canRedo)
        assertTrue(CoDrawingCanvasEngine.redo())
        assertEquals(2, CoDrawingCanvasEngine.state.value.elements.size)

        // Overlay mode state machine toggles cleanly between PASSTHROUGH_WATCH and CO_DRAW_ACTIVE
        assertEquals(CrossAppOverlayMode.PASSTHROUGH_WATCH, CoDrawingCanvasEngine.state.value.overlayMode)
        assertEquals(CrossAppOverlayMode.CO_DRAW_ACTIVE, CoDrawingCanvasEngine.toggleOverlayMode())
        assertEquals(CrossAppOverlayMode.PASSTHROUGH_WATCH, CoDrawingCanvasEngine.toggleOverlayMode())
    }

    @Test
    fun testExternalSseStreamClient_securityUrlAllowlistBlocksCleartextRemoteHttp() {
        assertTrue(ExternalSseStreamClient.isAllowedSseUrl("https://api.example.com/v1/events"))
        assertTrue(ExternalSseStreamClient.isAllowedSseUrl("http://127.0.0.1:18888/sse/board"))
        assertTrue(ExternalSseStreamClient.isAllowedSseUrl("http://localhost:18889/sse"))

        assertFalse("Remote cleartext HTTP must be blocked", ExternalSseStreamClient.isAllowedSseUrl("http://evil.example.com/sse"))
        assertFalse("File scheme must be blocked", ExternalSseStreamClient.isAllowedSseUrl("file:///etc/passwd"))
        assertFalse("Javascript scheme must be blocked", ExternalSseStreamClient.isAllowedSseUrl("javascript:alert(1)"))
        assertFalse("Blank URL must be blocked", ExternalSseStreamClient.isAllowedSseUrl("   "))
    }
}
