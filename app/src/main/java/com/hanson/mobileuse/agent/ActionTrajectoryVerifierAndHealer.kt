package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.CoordinateNormalizer
import com.hanson.mobileuse.accessibility.GestureController
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.IndexedUiElement
import org.json.JSONObject
import kotlin.math.abs

enum class FailureCategory(val code: String, val descriptionKo: String) {
    NONE("OK", "정상 전이 확인"),
    MODAL_POPUP_BLOCKING("MODAL_TRAP", "권한/광고/시스템 팝업이 화면을 가로막음"),
    NO_UI_TRANSITION_ON_CLICK("STALE_CLICK", "터치 신호는 전달되었으나 화면 UI 변화가 없음 (좌표 오차 또는 커스텀 뷰 필터링)"),
    INPUT_TEXT_NOT_REFLECTED("INPUT_MISMATCH", "입력창 포커스 미확보로 타이핑 텍스트가 필드에 반영되지 않음"),
    TRAJECTORY_NO_DISPLACEMENT("ZERO_DISPLACEMENT", "스와이프/드래그앤드롭 궤적 후 요소 이동이 감지되지 않음"),
    APP_LAUNCH_STALLED("APP_STALLED", "앱 실행 명령 후 목표 패키지로 포그라운드 전환이 지연됨")
}

data class TransitionVerificationReport(
    val verifiedSuccess: Boolean,
    val treeDeltaRatio: Float,
    val packageChanged: Boolean,
    val windowChanged: Boolean,
    val focusOrTextChanged: Boolean,
    val failureCategory: FailureCategory,
    val diagnosisKo: String,
    val recoveryAttempted: Boolean = false,
    val recoveryActionKo: String = "",
    val healedSuccess: Boolean = false,
    val finalSnapshot: AtomicSnapshot
) {
    fun toFormattedBadgeAndLog(): String {
        val pct = (treeDeltaRatio * 100).toInt()
        return if (verifiedSuccess && !recoveryAttempted) {
            "[검증 성공 · ΔUI ${pct}%] $diagnosisKo"
        } else if (recoveryAttempted && healedSuccess) {
            "[자기수복(Self-Heal) 완료 · ΔUI ${pct}%] 인지($diagnosisKo) → 복구($recoveryActionKo) → 재검증 성공"
        } else if (recoveryAttempted) {
            "[자기수복 시도 · ΔUI ${pct}%] 인지($diagnosisKo) → 복구($recoveryActionKo) 실행됨 (다음 스텝에서 우회 궤적 탐색)"
        } else {
            "[검증 실패 · ΔUI ${pct}%] $diagnosisKo"
        }
    }
}

/**
 * Closed-Loop State Transition Verifier & 3-Stage Self-Healing Engine (`인지 -> 복구 -> 재실행`).
 *
 * Solves the core problem of naive mobile agents that assume `dispatchGesture == true` means success:
 * 1. Compares `preSnapshot` (S_t) vs `postSnapshot` (S_{t+1}) to measure actual UI state transition.
 * 2. Knows what SUCCESS vs. FAILURE means for each tool (`click`, `input_text`, `swipe`, `drag_and_drop`, `manage_app`).
 * 3. When a step fails or leaves the screen stale, automatically diagnoses the cause (인지),
 *    executes an escalating recovery action (복구: 팝업 해체 / 오프셋 지터 탭 / 포커스 강제 획득 후 붙여넣기 / UID-2000 커널 입력 주입),
 *    and re-verifies the screen state (재실행 검증).
 */
class ActionTrajectoryVerifierAndHealer(private val context: Context) {

    companion object {
        private val MODAL_DISMISS_KEYWORDS = listOf(
            "앱 사용 중에만 허용", "이번만 허용", "허용", "확인", "닫기", "나중에", "건너뛰기", "취소", "동의"
        )

        /**
         * Computes the structural UI change ratio [0.0 .. 1.0] between two snapshots.
         */
        fun computeUiTreeDeltaRatio(pre: AtomicSnapshot, post: AtomicSnapshot): Float {
            if (pre.activePackage != post.activePackage) return 1.0f
            if (pre.indexedElements.isEmpty() && post.indexedElements.isEmpty()) return 0f
            if (pre.indexedElements.isEmpty() || post.indexedElements.isEmpty()) return 1.0f

            val preSigs = pre.indexedElements.map { elementSignature(it) }.toSet()
            val postSigs = post.indexedElements.map { elementSignature(it) }.toSet()

            val unionSize = (preSigs union postSigs).size.coerceAtLeast(1)
            val intersectionSize = (preSigs intersect postSigs).size
            return ((unionSize - intersectionSize).toFloat() / unionSize.toFloat()).coerceIn(0f, 1f)
        }

        private fun elementSignature(el: IndexedUiElement): String {
            val gridX = el.centerX / 24
            val gridY = el.centerY / 24
            return "${el.shortClass}|${el.text}|${el.contentDesc}|$gridX,$gridY|${el.focused}|${el.checked}"
        }
    }

    /**
     * Evaluates whether `toolName(args)` actually achieved its intended state transition between `preSnapshot` and `postSnapshot`.
     * If not, performs automatic 3-stage Self-Healing (`인지 -> 복구 -> 재실행`) and returns the final report.
     */
    fun verifyAndSelfHeal(
        toolName: String,
        args: JSONObject,
        initialOutcomeSucceeded: Boolean,
        preSnapshot: AtomicSnapshot,
        postSnapshot: AtomicSnapshot
    ): TransitionVerificationReport {
        // Non-GUI / metadata tools do not require screen delta verification
        if (toolName !in setOf("click", "long_press", "click_sequence", "input_text", "swipe", "drag_and_drop", "haptic_trajectory", "manage_app")) {
            return TransitionVerificationReport(
                verifiedSuccess = initialOutcomeSucceeded,
                treeDeltaRatio = computeUiTreeDeltaRatio(preSnapshot, postSnapshot),
                packageChanged = preSnapshot.activePackage != postSnapshot.activePackage,
                windowChanged = preSnapshot.activeWindowTitle != postSnapshot.activeWindowTitle,
                focusOrTextChanged = false,
                failureCategory = FailureCategory.NONE,
                diagnosisKo = "시스템/패스트레인 도구 즉시 실행 완료",
                finalSnapshot = postSnapshot
            )
        }

        val initialCheck = evaluateTransitionOnly(toolName, args, initialOutcomeSucceeded, preSnapshot, postSnapshot)
        if (initialCheck.verifiedSuccess) {
            return initialCheck
        }

        // =====================================================================
        // STAGE 2 & 3: SELF-HEALING (인지 -> 복구 -> 재실행 검증)
        // =====================================================================
        val recoveryDescription = executeRecoveryAndRetry(
            failureCategory = initialCheck.failureCategory,
            toolName = toolName,
            args = args,
            preSnapshot = preSnapshot,
            failedPostSnapshot = postSnapshot
        )

        // Wait for recovery UI settle and capture S_{t+2}
        try {
            Thread.sleep(240L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val healedSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)
            ?: postSnapshot

        val recheck = evaluateTransitionOnly(toolName, args, true, preSnapshot, healedSnapshot)

        return initialCheck.copy(
            verifiedSuccess = recheck.verifiedSuccess,
            treeDeltaRatio = recheck.treeDeltaRatio,
            packageChanged = recheck.packageChanged,
            windowChanged = recheck.windowChanged,
            focusOrTextChanged = recheck.focusOrTextChanged,
            recoveryAttempted = true,
            recoveryActionKo = recoveryDescription,
            healedSuccess = recheck.verifiedSuccess,
            finalSnapshot = healedSnapshot
        )
    }

    fun evaluateTransitionOnly(
        toolName: String,
        args: JSONObject,
        initialOutcomeSucceeded: Boolean,
        pre: AtomicSnapshot,
        post: AtomicSnapshot
    ): TransitionVerificationReport {
        val pkgChanged = pre.activePackage != post.activePackage
        val winChanged = pre.activeWindowTitle != post.activeWindowTitle
        val delta = computeUiTreeDeltaRatio(pre, post)

        val preFocused = pre.indexedElements.firstOrNull { it.focused }?.index
        val postFocused = post.indexedElements.firstOrNull { it.focused }?.index
        val preEditableTexts = pre.indexedElements.filter { it.editable }.map { it.text }
        val postEditableTexts = post.indexedElements.filter { it.editable }.map { it.text }
        val focusOrTextChanged = (preFocused != postFocused) || (preEditableTexts != postEditableTexts)

        when (toolName) {
            "manage_app" -> {
                val action = args.optString("action", "launch")
                if (action != "launch") {
                    return TransitionVerificationReport(
                        verifiedSuccess = initialOutcomeSucceeded,
                        treeDeltaRatio = delta,
                        packageChanged = pkgChanged,
                        windowChanged = winChanged,
                        focusOrTextChanged = focusOrTextChanged,
                        failureCategory = FailureCategory.NONE,
                        diagnosisKo = "앱 목록 조회 완료",
                        finalSnapshot = post
                    )
                }
                val ok = initialOutcomeSucceeded && (pkgChanged || delta >= 0.15f)
                return TransitionVerificationReport(
                    verifiedSuccess = ok,
                    treeDeltaRatio = delta,
                    packageChanged = pkgChanged,
                    windowChanged = winChanged,
                    focusOrTextChanged = focusOrTextChanged,
                    failureCategory = if (ok) FailureCategory.NONE else FailureCategory.APP_LAUNCH_STALLED,
                    diagnosisKo = if (ok) "포그라운드 패키지 전환 확인 (${post.activePackage})" else "앱 실행 후 화면 전환 지연 감지",
                    finalSnapshot = post
                )
            }

            "click", "long_press", "click_sequence" -> {
                // 1. Check if an OS permission dialog popped up
                val isPermissionOrSystemDialog = post.activePackage.contains("permissioncontroller", ignoreCase = true) ||
                    post.activePackage.contains("packageinstaller", ignoreCase = true)
                if (isPermissionOrSystemDialog) {
                    return TransitionVerificationReport(
                        verifiedSuccess = false,
                        treeDeltaRatio = delta,
                        packageChanged = pkgChanged,
                        windowChanged = winChanged,
                        focusOrTextChanged = false,
                        failureCategory = FailureCategory.MODAL_POPUP_BLOCKING,
                        diagnosisKo = "권한 요청 팝업 다이얼로그 감지",
                        finalSnapshot = post
                    )
                }

                // 2. If the click produced a real UI transition, package change, or focus change, it succeeded!
                val ok = pkgChanged || winChanged || delta >= 0.04f || focusOrTextChanged
                if (ok) {
                    return TransitionVerificationReport(
                        verifiedSuccess = true,
                        treeDeltaRatio = delta,
                        packageChanged = pkgChanged,
                        windowChanged = winChanged,
                        focusOrTextChanged = focusOrTextChanged,
                        failureCategory = FailureCategory.NONE,
                        diagnosisKo = when {
                            pkgChanged -> "앱/액티비티 전환 성공 (${post.activePackage})"
                            focusOrTextChanged -> "입력창 포커스 활성화 성공"
                            else -> "화면 UI 요소 상태 전이 확인"
                        },
                        finalSnapshot = post
                    )
                }

                val hasExplicitModal = post.indexedElements.any { el ->
                    MODAL_DISMISS_KEYWORDS.any { kw ->
                        (el.text.isNotBlank() && el.text.contains(kw, ignoreCase = true)) ||
                            (el.contentDesc.isNotBlank() && el.contentDesc.contains(kw, ignoreCase = true))
                    }
                }
                return TransitionVerificationReport(
                    verifiedSuccess = false,
                    treeDeltaRatio = delta,
                    packageChanged = false,
                    windowChanged = false,
                    focusOrTextChanged = false,
                    failureCategory = if (hasExplicitModal) FailureCategory.MODAL_POPUP_BLOCKING else FailureCategory.NO_UI_TRANSITION_ON_CLICK,
                    diagnosisKo = if (hasExplicitModal) "팝업/모달 창이 클릭을 가로막음" else "클릭 후 화면 상태 변화 없음 (ΔUI 0%)",
                    finalSnapshot = post
                )
            }

            "input_text" -> {
                val targetText = args.optString("text", "").trim()
                val textAppeared = targetText.isNotEmpty() && post.indexedElements.any { el ->
                    el.text.contains(targetText, ignoreCase = true) ||
                        (targetText.length >= 2 && el.text.contains(targetText.take(2), ignoreCase = true))
                }
                val ok = textAppeared || focusOrTextChanged || delta >= 0.08f
                return TransitionVerificationReport(
                    verifiedSuccess = ok,
                    treeDeltaRatio = delta,
                    packageChanged = pkgChanged,
                    windowChanged = winChanged,
                    focusOrTextChanged = focusOrTextChanged,
                    failureCategory = if (ok) FailureCategory.NONE else FailureCategory.INPUT_TEXT_NOT_REFLECTED,
                    diagnosisKo = if (ok) "텍스트('$targetText') 입력 및 화면 반영 확인" else "입력창 포커스 미확보로 텍스트('$targetText') 미반영",
                    finalSnapshot = post
                )
            }

            "swipe", "drag_and_drop", "haptic_trajectory" -> {
                val ok = delta >= 0.06f || pkgChanged || winChanged
                return TransitionVerificationReport(
                    verifiedSuccess = ok,
                    treeDeltaRatio = delta,
                    packageChanged = pkgChanged,
                    windowChanged = winChanged,
                    focusOrTextChanged = focusOrTextChanged,
                    failureCategory = if (ok) FailureCategory.NONE else FailureCategory.TRAJECTORY_NO_DISPLACEMENT,
                    diagnosisKo = if (ok) "햅틱 궤적 이동에 따른 화면 스크롤/배치 변화 확인" else "스와이프/드래그 후 요소 이동이 감지되지 않음",
                    finalSnapshot = post
                )
            }

            else -> {
                return TransitionVerificationReport(
                    verifiedSuccess = initialOutcomeSucceeded,
                    treeDeltaRatio = delta,
                    packageChanged = pkgChanged,
                    windowChanged = winChanged,
                    focusOrTextChanged = focusOrTextChanged,
                    failureCategory = FailureCategory.NONE,
                    diagnosisKo = "도구 실행 완료",
                    finalSnapshot = post
                )
            }
        }
    }

    /**
     * Executes concrete recovery actions based on the diagnosed FailureCategory,
     * then re-executes the intended gesture/input.
     */
    private fun executeRecoveryAndRetry(
        failureCategory: FailureCategory,
        toolName: String,
        args: JSONObject,
        preSnapshot: AtomicSnapshot,
        failedPostSnapshot: AtomicSnapshot
    ): String {
        val svc = HansonAccessibilityService.instance

        return when (failureCategory) {
            FailureCategory.MODAL_POPUP_BLOCKING -> {
                val dismissBtn = failedPostSnapshot.indexedElements.firstOrNull { el ->
                    MODAL_DISMISS_KEYWORDS.any { kw -> el.text.contains(kw) || el.contentDesc.contains(kw) }
                }
                if (dismissBtn != null && svc != null) {
                    GestureController.tap(svc, dismissBtn.centerX.toFloat(), dismissBtn.centerY.toFloat(), 80L)
                    "가로막은 팝업 버튼('${dismissBtn.text.ifBlank { dismissBtn.contentDesc }}') 자동 해체 탭"
                } else if (svc != null) {
                    GestureController.performGlobalKey(svc, "BACK")
                    "가로막은 모달 해체를 위해 전역 BACK 키 주입"
                } else {
                    "모달 감지"
                }
            }

            FailureCategory.NO_UI_TRANSITION_ON_CLICK -> {
                val coords = resolveCoordinates(args, failedPostSnapshot)
                if (coords != null) {
                    val px = coords.first
                    val py = coords.second
                    // 1. Try spatial jitter (+12, -8) with longer 125ms press duration and symmetric coordinate clamping
                    val jitterX = (px + 12).coerceIn(10, failedPostSnapshot.screenWidth - 10)
                    val jitterY = (py - 8).coerceIn(10, failedPostSnapshot.screenHeight - 10)
                    if (svc != null) {
                        GestureController.tap(svc, jitterX.toFloat(), jitterY.toFloat(), 125L)
                    }
                    // 2. Also escalate to UID-2000 Native OS CLI kernel tap in case WebView/Flutter ignored A11y gesture
                    execOsCliFallback("tap $px $py")
                    "오프셋 지터 탭($jitterX,$jitterY, 125ms) + UID-2000 커널 input tap($px,$py) 이중 재실행"
                } else {
                    "좌표 재산출 시도"
                }
            }

            FailureCategory.INPUT_TEXT_NOT_REFLECTED -> {
                val textToType = args.optString("text", "")
                val submitEnter = args.optBoolean("submit_enter", true)
                // Locate editable field or search bar candidate
                val editableCandidate = failedPostSnapshot.indexedElements.firstOrNull { it.editable }
                    ?: failedPostSnapshot.indexedElements.firstOrNull {
                        it.text.contains("검색") || it.contentDesc.contains("검색") ||
                            it.shortClass.contains("EditText", ignoreCase = true)
                    }
                if (svc != null && editableCandidate != null) {
                    GestureController.tap(
                        svc,
                        editableCandidate.centerX.toFloat(),
                        editableCandidate.centerY.toFloat(),
                        90L
                    )
                    try {
                        Thread.sleep(280L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    GestureController.inputText(
                        svc,
                        text = textToType,
                        targetX = editableCandidate.centerX.toFloat(),
                        targetY = editableCandidate.centerY.toFloat(),
                        clearExisting = false
                    )
                    if (submitEnter) {
                        GestureController.performGlobalKey(svc, "ENTER")
                        execOsCliFallback("key 66")
                    }
                    "입력창(${editableCandidate.centerX},${editableCandidate.centerY}) 강제 포커스 탭 → 클립보드 붙여넣기 → ENTER 재전송"
                } else {
                    execOsCliFallback("text $textToType")
                    if (submitEnter) execOsCliFallback("key 66")
                    "UID-2000 OS CLI 커널 텍스트 스트림 직주입"
                }
            }

            FailureCategory.TRAJECTORY_NO_DISPLACEMENT -> {
                val toolName = args.optString("toolName", "")
                if (toolName == "drag_and_drop" || args.has("start_x")) {
                    val sx = args.optInt("start_x", 0)
                    val sy = args.optInt("start_y", 0)
                    val ex = args.optInt("end_x", 0)
                    val ey = args.optInt("end_y", 0)
                    if (svc != null) {
                        GestureController.dragAndDrop(svc, sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), 700L, 800L)
                    }
                    execOsCliFallback("swipe $sx $sy $ex $ey 800")
                    "드래그앤드롭 홀드 시간(700ms) 연장 및 슬라이드 재시도"
                } else {
                    val dir = args.optString("direction", "up")
                    val swipeCoords = CoordinateNormalizer.computeDirectionalSwipe(
                        dir,
                        failedPostSnapshot.screenWidth,
                        failedPostSnapshot.screenHeight
                    )
                    execOsCliFallback(
                        "swipe ${swipeCoords.startX} ${swipeCoords.startY} ${swipeCoords.endX} ${swipeCoords.endY} 480"
                    )
                    "UID-2000 커널 레벨 광폭 스와이프(${swipeCoords.startX},${swipeCoords.startY} -> ${swipeCoords.endX},${swipeCoords.endY}) 재실행"
                }
            }

            FailureCategory.APP_LAUNCH_STALLED -> {
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                "앱 콜드 스타트 대기(500ms) 후 포그라운드 재검증"
            }

            FailureCategory.NONE -> "정상"
        }
    }

    private fun resolveCoordinates(args: JSONObject, snapshot: AtomicSnapshot): Pair<Int, Int>? {
        if (args.has("element_index")) {
            val idx = args.optInt("element_index", -1)
            val el = snapshot.indexedElements.firstOrNull { it.index == idx }
            if (el != null) return el.centerX to el.centerY
        }
        if (args.has("norm_x") && args.has("norm_y")) {
            return CoordinateNormalizer.normalizedToPixel(
                args.optInt("norm_x", 500),
                args.optInt("norm_y", 500),
                snapshot.screenWidth,
                snapshot.screenHeight
            )
        }
        return null
    }

    private fun execOsCliFallback(cmd: String) {
        try {
            AgentToolExecutionHarness(context).execute(
                "os_cli_exec",
                JSONObject().put("command", cmd),
                null
            )
        } catch (_: Exception) {
        }
    }
}
