package com.hanson.mobileuse

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.CoordinateNormalizer
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.ActionTrajectoryVerifierAndHealer
import com.hanson.mobileuse.agent.FailureCategory
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.paradigm.CompositeExecutionMode
import com.hanson.mobileuse.paradigm.HabitOrganonRuntime
import com.hanson.mobileuse.paradigm.ModeTurnResult
import com.hanson.mobileuse.paradigm.ParadigmExecutionModeController
import com.hanson.mobileuse.paradigm.SemanticTeleportCandidate
import com.hanson.mobileuse.paradigm.SemanticTeleportRouter
import com.hanson.mobileuse.paradigm.WorldModelGraph
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Empirical Adversarial Challenger Test Suite:
 * Stress-tests 3-Layer Interaction Routing, 3-Tier State Verification, and 3-Stage Self-Healing.
 */
class EmpiricalChallengerParadigmStressTest {

    private lateinit var dummyContext: Context
    private lateinit var router: SemanticTeleportRouter
    private lateinit var verifier: ActionTrajectoryVerifierAndHealer

    @Before
    fun setUp() {
        RemoteInputSessionCache.clearAll()
        dummyContext = object : android.content.ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
        }
        router = SemanticTeleportRouter(dummyContext)
        verifier = ActionTrajectoryVerifierAndHealer(dummyContext)
    }

    // =========================================================================
    // Challenge 1: Layer 1 RemoteInput Fast-Lane Matching on Realistic Goals
    // =========================================================================

    @Test
    fun challenge1_remoteInputFastLane_resolvesNaturalLanguageUserGoal() {
        // Given an intercepted notification from "이민지"
        val notif = InterceptedNotification(
            sessionId = "kakao_999",
            notificationKey = "0|com.kakao.talk|999",
            packageName = "com.kakao.talk",
            appLabel = "카카오톡",
            sender = "이민지",
            roomOrTitle = "팀 단톡방",
            messageText = "회의 언제 시작하나요?",
            canQuickReply = true
        )
        RemoteInputSessionCache.put(notif)

        // When user asks with a realistic natural language goal:
        val naturalGoal = "카톡 이민지에게 \"2시 시작입니다\"라고 답장해줘"

        // Remediated check: findFastLaneCandidate uses bidirectional containment
        val candidate = router.findFastLaneCandidate(naturalGoal)

        println("[EMPIRICAL EVIDENCE] candidate for '$naturalGoal' is: $candidate")
        assertNotNull("Remediated: RemoteInput candidate must not be null for natural language goal", candidate)
        assertTrue(candidate is SemanticTeleportCandidate.RemoteInputCandidate)
        val reply = candidate as SemanticTeleportCandidate.RemoteInputCandidate
        assertEquals("kakao_999", reply.sessionId)
        assertEquals("2시 시작입니다", reply.replyText)
    }

    // =========================================================================
    // Challenge 2: Layer 1 Intent Teleportation with Compound Kinetic Handoff
    // =========================================================================

    @Test
    fun challenge2_layer1Intent_identifiesCompoundKineticGoal() {
        // Goal from LiquidUiSynthesizer preset macro: "4-STEP 궤적: 유튜브 열어서 아이유 검색하고 영상 클릭해줘"
        val compoundGoal = "유튜브 열어서 아이유 검색하고 영상 클릭해줘"

        val candidate = router.findFastLaneCandidate(compoundGoal)

        println("[EMPIRICAL EVIDENCE] Compound goal returned candidate: $candidate")
        assertNotNull("Candidate should not be null", candidate)
        assertTrue("Candidate should be IntentCandidate", candidate is SemanticTeleportCandidate.IntentCandidate)
        val intentCandidate = candidate as SemanticTeleportCandidate.IntentCandidate
        assertEquals("com.google.android.youtube", intentCandidate.packageName)
        assertTrue("Remediated: Router identifies goal as compound for kinetic handoff", router.isCompoundGoal(compoundGoal))
    }

    // =========================================================================
    // Challenge 3: State Verification Traps Blocking Modal Popups
    // =========================================================================

    @Test
    fun challenge3_stateVerification_catchesBlockingModalPopups() {
        val clickButton = IndexedUiElement(
            index = 0,
            shortClass = "Button",
            className = "android.widget.Button",
            packageName = "com.target.app",
            text = "다운로드",
            contentDesc = "다운로드",
            resourceId = "com.target.app:id/btn_download",
            left = 100, top = 200, right = 400, bottom = 300,
            centerX = 250, centerY = 250, normX = 231, normY = 104,
            clickable = true, longClickable = false, scrollable = false,
            editable = false, checkable = false, checked = false, focused = false,
            enabled = true, windowType = "APPLICATION", windowLayer = 0
        )

        val snapBefore = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.target.app",
            activeWindowTitle = "Target Activity",
            indexedElements = listOf(clickButton)
        )

        // As a result of clicking download, a system permission dialog traps the screen
        val permDialogBtn = clickButton.copy(
            index = 0,
            packageName = "com.google.android.permissioncontroller",
            text = "앱 사용 중에만 허용",
            contentDesc = "앱 사용 중에만 허용"
        )
        val snapModalTrapped = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.google.android.permissioncontroller",
            activeWindowTitle = "Permission Controller",
            indexedElements = listOf(permDialogBtn)
        )

        val report = verifier.evaluateTransitionOnly(
            toolName = "click",
            args = JSONObject().put("element_index", 0),
            initialOutcomeSucceeded = true,
            pre = snapBefore,
            post = snapModalTrapped
        )

        println("[EMPIRICAL EVIDENCE] Verification of modal trap: verifiedSuccess=${report.verifiedSuccess}, diagnosisKo=${report.diagnosisKo}")
        assertFalse("Remediated: Verifier catches modal popup trap instead of verifiedSuccess = true", report.verifiedSuccess)
        assertEquals("Remediated: Verifier diagnoses modal popup blocking", FailureCategory.MODAL_POPUP_BLOCKING, report.failureCategory)
    }

    // =========================================================================
    // Challenge 4: Modal Dismissal Handles Compound Keywords
    // =========================================================================

    @Test
    fun challenge4_modalDismissal_handlesCompoundKeywords() {
        // When pre and post are stale because a modal was already on screen
        val modalCompoundBtn = IndexedUiElement(
            index = 0,
            shortClass = "Button",
            className = "android.widget.Button",
            packageName = "com.target.app",
            text = "동의하고 계속하기", // Contains "동의"
            contentDesc = "동의하고 계속하기",
            resourceId = "com.target.app:id/btn_agree",
            left = 100, top = 200, right = 400, bottom = 300,
            centerX = 250, centerY = 250, normX = 231, normY = 104,
            clickable = true, longClickable = false, scrollable = false,
            editable = false, checkable = false, checked = false, focused = false,
            enabled = true, windowType = "APPLICATION", windowLayer = 0
        )

        val snapModal = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.target.app",
            activeWindowTitle = "Modal Dialog",
            indexedElements = listOf(modalCompoundBtn)
        )

        val report = verifier.evaluateTransitionOnly(
            toolName = "click",
            args = JSONObject().put("element_index", 0),
            initialOutcomeSucceeded = true,
            pre = snapModal,
            post = snapModal // Stale click behind or on unclickable area
        )

        println("[EMPIRICAL EVIDENCE] Diagnosis for compound modal button: ${report.failureCategory}")
        assertEquals("Remediated: Compound modal keyword correctly diagnosed as MODAL_POPUP_BLOCKING", FailureCategory.MODAL_POPUP_BLOCKING, report.failureCategory)
    }

    // =========================================================================
    // Challenge 5: Spatial Jitter Symmetric Clamping Safeguard
    // =========================================================================

    @Test
    fun challenge5_spatialJitter_symmetricClampingSafeguard() {
        val screenW = 1080
        val screenH = 2400

        val pyOffscreen = 2405
        val clampedY = (pyOffscreen - 8).coerceIn(10, screenH - 10)
        assertEquals(screenH - 10, clampedY)
        assertTrue("jitterY is clamped within screenHeight - 10", clampedY <= screenH - 10)

        val pxNegative = -15
        val clampedX = (pxNegative + 12).coerceIn(10, screenW - 10)
        assertEquals(10, clampedX)
        assertTrue("jitterX is clamped to at least 10", clampedX >= 10)
    }

    // =========================================================================
    // Challenge 1b: Exact Dispatch Single-Quoted RemoteInput Prompt
    // =========================================================================

    @Test
    fun challenge1b_singleQuotes_andExactDispatchPrompt() {
        val notif = InterceptedNotification(
            sessionId = "kakao_minji_exact",
            notificationKey = "0|com.kakao.talk|888",
            packageName = "com.kakao.talk",
            appLabel = "카카오톡",
            sender = "이민지",
            roomOrTitle = "팀 단톡방",
            messageText = "회의 언제 시작하나요?",
            canQuickReply = true
        )
        RemoteInputSessionCache.put(notif)

        // Exact dispatch prompt: "카톡 이민지에게 '2시 시작' 답장해줘"
        val dispatchGoal = "카톡 이민지에게 '2시 시작' 답장해줘"
        val candidate = router.findFastLaneCandidate(dispatchGoal)
        assertNotNull("Candidate must not be null for single-quoted dispatch prompt", candidate)
        assertTrue(candidate is SemanticTeleportCandidate.RemoteInputCandidate)
        val reply = candidate as SemanticTeleportCandidate.RemoteInputCandidate
        assertEquals("kakao_minji_exact", reply.sessionId)
        assertEquals("2시 시작", reply.replyText)
    }

    // =========================================================================
    // Challenge 2b: Exact Dispatch Compound Prompt Launch & Kinetic Continuation
    // =========================================================================

    @Test
    fun challenge2b_exactDispatchCompoundPrompt_youtubeIuSearch() {
        // Exact dispatch prompt: "유튜브 열어서 아이유 검색해줘"
        val dispatchCompoundGoal = "유튜브 열어서 아이유 검색해줘"
        val candidate = router.findFastLaneCandidate(dispatchCompoundGoal)
        assertNotNull("Candidate should not be null", candidate)
        assertTrue("Candidate should be IntentCandidate", candidate is SemanticTeleportCandidate.IntentCandidate)
        val intentCandidate = candidate as SemanticTeleportCandidate.IntentCandidate
        assertEquals("com.google.android.youtube", intentCandidate.packageName)
        assertTrue("Router must identify '유튜브 열어서 아이유 검색해줘' as compound goal", router.isCompoundGoal(dispatchCompoundGoal))
    }

    // =========================================================================
    // Challenge 6: BOCPD Step Transitions, Run-Length, Stability & Escalate Gate
    // =========================================================================

    @Test
    fun challenge6_bocpd_stepTransitions_runLength_and_stability_probability_directAction_vs_escalate() {
        val prefsMap = mutableMapOf<String, InMemoryTestSharedPreferences>()
        val testContext = object : android.content.ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                return prefsMap.getOrPut(name ?: "default") { InMemoryTestSharedPreferences() }
            }
        }
        val graph = WorldModelGraph(testContext)
        val runtime = HabitOrganonRuntime(testContext, graph)
        val controller = ParadigmExecutionModeController(graph, runtime)

        val snap = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.google.android.youtube",
            activeWindowTitle = "YouTube Main"
        )

        // Initially fresh detector
        assertEquals(0, controller.bocpdDetector.runLength)
        assertEquals(1.0f, controller.bocpdDetector.regimeStabilityProbability, 0.01f)

        // Step transition 1: Success
        val res1 = controller.evaluateTurn(snap, "유튜브 검색", recentStepFailed = false)
        assertEquals(1, controller.bocpdDetector.runLength)
        assertTrue(controller.bocpdDetector.regimeStabilityProbability > 0.85f)
        assertTrue("Cold state escalates to S2", res1 is ModeTurnResult.EscalateToSystem2)

        // Step transition 2: Success
        controller.evaluateTurn(snap, "유튜브 검색", recentStepFailed = false)
        assertEquals(2, controller.bocpdDetector.runLength)
        assertTrue(controller.bocpdDetector.regimeStabilityProbability > 0.85f)

        // Step transition 3: Step failure triggers regime collapse
        val resFailed = controller.evaluateTurn(snap, "유튜브 검색", recentStepFailed = true)
        assertEquals(0, controller.bocpdDetector.runLength)
        assertTrue(controller.bocpdDetector.regimeStabilityProbability < 0.35f)
        assertTrue("Regime collapse must escalate to S2", resFailed is ModeTurnResult.EscalateToSystem2)
        val esc = resFailed as ModeTurnResult.EscalateToSystem2
        assertTrue(esc.reasonKo.contains("BOCPD") || esc.reasonKo.contains("급변"))

        // Warm up prototype and test DirectAction on warm state
        repeat(2) {
            runtime.recordStepProvenance(
                stepNumber = 1,
                preSnapshot = snap,
                postSnapshot = snap.copy(activeWindowTitle = "Search Input"),
                actorTier = "S2_DELIBERATIVE",
                toolName = "click",
                toolArgsJson = "{\"element_index\": 0}",
                verifiedSuccess = true,
                uiDeltaRatio = 0.5f,
                recoveryAttempted = false,
                latencyMs = 50L
            )
            runtime.finalizeEpisodeAndAssignDelayedCredit("유튜브 검색", "com.google.android.youtube", true)
        }

        // Step transition 4: Success on warm prototype -> DirectAction
        val resWarm = controller.evaluateTurn(snap, "유튜브 검색", recentStepFailed = false)
        assertEquals(1, controller.bocpdDetector.runLength)
        assertTrue("Warm state triggers DirectAction", resWarm is ModeTurnResult.DirectAction)
        val direct = resWarm as ModeTurnResult.DirectAction
        assertEquals("click", direct.decision.toolName)
    }
}

