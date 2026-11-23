package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.CoordinateNormalizer
import com.hanson.mobileuse.accessibility.GestureController
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.service.FloatingHudService
import org.json.JSONObject

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
 * 3. Safely diagnoses blocking modal/permission popups WITHOUT blindly auto-clicking arbitrary
 *    confirm/consent/allow dialogs, and avoids A11y+CLI double-taps during single-channel recovery.
 */
class ActionTrajectoryVerifierAndHealer(private val context: Context) {

    companion object {
        // Used strictly for READ-ONLY modal diagnosis in evaluateTransitionOnly (never blind auto-click).
        private val MODAL_DETECTION_KEYWORDS = listOf(
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

        /**
         * Minitap `mobile-use`-inspired deterministic input verification:
         * Confirms entered text via Korean/phonetic normalization or detects password bullet masking (`•`, `●`, `*`).
         */
        fun verifyInputTextDeterministic(expectedText: String, post: AtomicSnapshot): Boolean {
            val trimmed = expectedText.trim()
            if (trimmed.isEmpty()) return false
            val expectedNorm = AgentToolExecutionHarness.normalizeKoreanUiToken(trimmed)
            val minBulletLen = minOf(trimmed.length, 4)

            return post.indexedElements.any { el ->
                val rawText = el.text.trim()
                if (rawText.isEmpty()) return@any false
                if (rawText.contains(trimmed, ignoreCase = true)) return@any true
                if (expectedNorm.length >= 2) {
                    val actualNorm = AgentToolExecutionHarness.normalizeKoreanUiToken(rawText)
                    if (actualNorm.contains(expectedNorm)) return@any true
                }
                val bulletCount = rawText.count { it == '•' || it == '●' || it == '*' || it == '·' }
                el.editable && bulletCount >= minBulletLen
            }
        }

        private fun elementSignature(el: IndexedUiElement): String {
            val gridX = el.centerX / 24
            val gridY = el.centerY / 24
            return "${el.shortClass}|${el.text}|${el.contentDesc}|$gridX,$gridY|${el.focused}|${el.checked}"
        }
    }

    private inline fun <T> withTemporaryHudPassThrough(block: () -> T): T {
        FloatingHudService.setPassThroughTemporarily(true)
        return try {
            block()
        } finally {
            FloatingHudService.setPassThroughTemporarily(false)
        }
    }

    /**
     * Evaluates whether `toolName(args)` actually achieved its intended state transition between `preSnapshot` and `postSnapshot`.
     * If not, performs safe, single-channel Self-Healing (`인지 -> 복구 -> 재실행`) and returns the final report.
     */
    fun verifyAndSelfHeal(
        toolName: String,
        args: JSONObject,
        initialOutcomeSucceeded: Boolean,
        preSnapshot: AtomicSnapshot,
        postSnapshot: AtomicSnapshot
    ): TransitionVerificationReport {
        // Non-GUI / metadata tools do not require screen delta verification
        if (toolName !in setOf(
                "click", "long_press", "click_sequence", "input_text",
                "vault_blind_inject", "swipe", "drag_and_drop", "haptic_trajectory", "manage_app"
            )
        ) {
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

        // Never blindly auto-click permission / confirmation / consent popups; surface them to VLM/user.
        if (initialCheck.failureCategory == FailureCategory.MODAL_POPUP_BLOCKING) {
            return initialCheck.copy(
                recoveryAttempted = false,
                recoveryActionKo = "권한/확인/동의 모달 감지됨 — 무단 클릭 방지를 위해 자동 클릭을 수행하지 않고 VLM/사용자 확인으로 위임합니다.",
                healedSuccess = false
            )
        }

        // =====================================================================
        // STAGE 2 & 3: SELF-HEALING (인지 -> 복구 -> 재실행 검증)
        // =====================================================================
        val (recoveryDispatchedOk, recoveryDescription) = executeRecoveryAndRetry(
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

        val recheck = evaluateTransitionOnly(
            toolName = toolName,
            args = args,
            initialOutcomeSucceeded = recoveryDispatchedOk,
            pre = preSnapshot,
            post = healedSnapshot
        )

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
                    MODAL_DETECTION_KEYWORDS.any { kw ->
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

            "input_text", "vault_blind_inject" -> {
                val targetText = args.optString("text", "").trim()
                val textAppeared = if (toolName == "vault_blind_inject") {
                    verifyInputTextDeterministic("••••", post)
                } else {
                    verifyInputTextDeterministic(targetText, post)
                }
                // When press_enter/submit_enter=true, submitting immediately clears the EditText or triggers loading,
                // so if initialOutcomeSucceeded is true (ACTION_SET_TEXT succeeded), do not double-type in recovery!
                val ok = initialOutcomeSucceeded || textAppeared || focusOrTextChanged || delta >= 0.04f
                val displayLabel = if (toolName == "vault_blind_inject") {
                    args.optString("handle", args.optString("vault_key", "vault secret")).ifBlank { "vault secret" }
                } else {
                    targetText
                }
                return TransitionVerificationReport(
                    verifiedSuccess = ok,
                    treeDeltaRatio = delta,
                    packageChanged = pkgChanged,
                    windowChanged = winChanged,
                    focusOrTextChanged = focusOrTextChanged,
                    failureCategory = if (ok) FailureCategory.NONE else FailureCategory.INPUT_TEXT_NOT_REFLECTED,
                    diagnosisKo = if (ok) "텍스트('$displayLabel') 입력 및 전송 확인" else "입력창 포커스 미확보로 텍스트('$displayLabel') 미반영",
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
     * Executes concrete single-channel recovery actions based on the diagnosed FailureCategory
     * (never firing A11y + CLI double-taps, and never blindly auto-clicking arbitrary modal popups).
     * Returns `Pair(dispatchedOk, descriptionKo)`.
     */
    private fun executeRecoveryAndRetry(
        failureCategory: FailureCategory,
        toolName: String,
        args: JSONObject,
        preSnapshot: AtomicSnapshot,
        failedPostSnapshot: AtomicSnapshot
    ): Pair<Boolean, String> {
        val svc = HansonAccessibilityService.instance

        return when (failureCategory) {
            FailureCategory.MODAL_POPUP_BLOCKING -> {
                false to "권한/확인/동의 모달 감지됨 — 자동 클릭 미수행 (VLM/사용자 확인 필요)"
            }

            FailureCategory.NO_UI_TRANSITION_ON_CLICK -> {
                val coords = resolveCoordinates(args, failedPostSnapshot)
                    ?: resolveCoordinates(args, preSnapshot)
                if (coords != null) {
                    val px = coords.first
                    val py = coords.second
                    val jitterX = (px + 12).coerceIn(10, (failedPostSnapshot.screenWidth - 10).coerceAtLeast(10))
                    val jitterY = (py - 8).coerceIn(10, (failedPostSnapshot.screenHeight - 10).coerceAtLeast(10))
                    if (svc != null) {
                        val a11yOk = withTemporaryHudPassThrough {
                            GestureController.tap(svc, jitterX.toFloat(), jitterY.toFloat(), 125L)
                        }
                        if (a11yOk) {
                            true to "오프셋 지터 탭($jitterX,$jitterY, 125ms) 단일 재실행"
                        } else {
                            val cliOk = withTemporaryHudPassThrough { execOsCliFallback("tap $px $py") }
                            cliOk to if (cliOk) {
                                "A11y 탭 실패로 OS CLI 커널 tap($px,$py) 폴백 재실행"
                            } else {
                                "오프셋 지터 탭 및 OS CLI 폴백 실패"
                            }
                        }
                    } else {
                        val cliOk = withTemporaryHudPassThrough { execOsCliFallback("tap $px $py") }
                        cliOk to if (cliOk) {
                            "OS CLI 커널 tap($px,$py) 폴백 재실행"
                        } else {
                            "OS CLI 커널 tap($px,$py) 폴백 실패"
                        }
                    }
                } else {
                    false to "좌표 재산출 불가"
                }
            }

            FailureCategory.INPUT_TEXT_NOT_REFLECTED -> {
                val editableCandidate = failedPostSnapshot.indexedElements.firstOrNull { it.editable }
                    ?: failedPostSnapshot.indexedElements.firstOrNull {
                        it.text.contains("검색") || it.contentDesc.contains("검색") ||
                            it.shortClass.contains("EditText", ignoreCase = true)
                    }
                if (toolName == "vault_blind_inject") {
                    val tapped = if (svc != null && editableCandidate != null) {
                        withTemporaryHudPassThrough {
                            GestureController.tap(
                                svc,
                                editableCandidate.centerX.toFloat(),
                                editableCandidate.centerY.toFloat(),
                                90L
                            )
                        }
                    } else false
                    tapped to "블라인드 볼트 입력창 포커스 복구 (평문 CLI 노출 차단)"
                } else {
                    val textToType = args.optString("text", "")
                    val pressEnter = if (args.has("press_enter")) {
                        args.optBoolean("press_enter", true)
                    } else {
                        args.optBoolean("submit_enter", true)
                    }
                    if (svc != null && editableCandidate != null) {
                        withTemporaryHudPassThrough {
                            GestureController.tap(
                                svc,
                                editableCandidate.centerX.toFloat(),
                                editableCandidate.centerY.toFloat(),
                                90L
                            )
                        }
                        try {
                            Thread.sleep(280L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        val typedOk = withTemporaryHudPassThrough {
                            GestureController.inputText(
                                svc,
                                text = textToType,
                                targetX = editableCandidate.centerX.toFloat(),
                                targetY = editableCandidate.centerY.toFloat(),
                                clearExisting = false
                            )
                        }
                        if (typedOk && pressEnter) {
                            val enterOk = GestureController.performGlobalKey(svc, "ENTER")
                            if (!enterOk) {
                                execOsCliFallback("key 66")
                            }
                        }
                        typedOk to "입력창(${editableCandidate.centerX},${editableCandidate.centerY}) 강제 포커스 탭 → 텍스트 입력 재시도"
                    } else {
                        val cliOk = execOsCliFallback("text $textToType")
                        val finalOk = if (cliOk && pressEnter) execOsCliFallback("key 66") else cliOk
                        finalOk to if (finalOk) {
                            "UID-2000 OS CLI 커널 텍스트 스트림 주입 완료"
                        } else {
                            "UID-2000 OS CLI 커널 텍스트 스트림 주입 실패"
                        }
                    }
                }
            }

            FailureCategory.TRAJECTORY_NO_DISPLACEMENT -> {
                val isDragTool = toolName == "drag_and_drop" ||
                    args.has("from_element_index") ||
                    args.has("to_element_index")
                if (isDragTool) {
                    val screenW = failedPostSnapshot.screenWidth
                    val screenH = failedPostSnapshot.screenHeight
                    val fromEl = if (args.has("from_element_index")) {
                        val idx = args.optInt("from_element_index", -1)
                        if (idx >= 0) {
                            failedPostSnapshot.indexedElements.firstOrNull { it.index == idx }
                                ?: preSnapshot.indexedElements.firstOrNull { it.index == idx }
                        } else null
                    } else null
                    val toEl = if (args.has("to_element_index")) {
                        val idx = args.optInt("to_element_index", -1)
                        if (idx >= 0) {
                            failedPostSnapshot.indexedElements.firstOrNull { it.index == idx }
                                ?: preSnapshot.indexedElements.firstOrNull { it.index == idx }
                        } else null
                    } else null

                    val (sx, sy) = if (fromEl != null) {
                        fromEl.centerX to fromEl.centerY
                    } else {
                        CoordinateNormalizer.normalizedToPixel(
                            args.optInt("start_norm_x", 300),
                            args.optInt("start_norm_y", 600),
                            screenW,
                            screenH
                        )
                    }
                    val (ex, ey) = if (toEl != null) {
                        toEl.centerX to toEl.centerY
                    } else {
                        CoordinateNormalizer.normalizedToPixel(
                            args.optInt("end_norm_x", 700),
                            args.optInt("end_norm_y", 400),
                            screenW,
                            screenH
                        )
                    }

                    if (svc != null) {
                        val a11yOk = withTemporaryHudPassThrough {
                            GestureController.dragAndDrop(
                                svc,
                                sx.toFloat(),
                                sy.toFloat(),
                                ex.toFloat(),
                                ey.toFloat(),
                                700L,
                                800L
                            )
                        }
                        if (a11yOk) {
                            true to "드래그앤드롭 홀드 시간(700ms) 연장 재시도 ($sx,$sy -> $ex,$ey)"
                        } else {
                            val cliOk = withTemporaryHudPassThrough {
                                execOsCliFallback("swipe $sx $sy $ex $ey 800")
                            }
                            cliOk to if (cliOk) {
                                "A11y 드래그 실패로 OS CLI 슬라이드 폴백 재시도 ($sx,$sy -> $ex,$ey)"
                            } else {
                                "드래그앤드롭 재시도 실패 ($sx,$sy -> $ex,$ey)"
                            }
                        }
                    } else {
                        val cliOk = withTemporaryHudPassThrough {
                            execOsCliFallback("swipe $sx $sy $ex $ey 800")
                        }
                        cliOk to if (cliOk) {
                            "OS CLI 드래그 슬라이드 폴백 재시도 ($sx,$sy -> $ex,$ey)"
                        } else {
                            "OS CLI 드래그 슬라이드 폴백 실패 ($sx,$sy -> $ex,$ey)"
                        }
                    }
                } else {
                    val dir = args.optString("direction", "up")
                    val swipeCoords = if (args.has("start_norm_x") && args.has("end_norm_x")) {
                        val (sx, sy) = CoordinateNormalizer.normalizedToPixel(
                            args.optInt("start_norm_x", 500),
                            args.optInt("start_norm_y", 700),
                            failedPostSnapshot.screenWidth,
                            failedPostSnapshot.screenHeight
                        )
                        val (ex, ey) = CoordinateNormalizer.normalizedToPixel(
                            args.optInt("end_norm_x", 500),
                            args.optInt("end_norm_y", 300),
                            failedPostSnapshot.screenWidth,
                            failedPostSnapshot.screenHeight
                        )
                        CoordinateNormalizer.SwipeVector(sx, sy, ex, ey)
                    } else {
                        CoordinateNormalizer.computeDirectionalSwipe(
                            dir,
                            failedPostSnapshot.screenWidth,
                            failedPostSnapshot.screenHeight
                        )
                    }

                    if (svc != null) {
                        val a11yOk = withTemporaryHudPassThrough {
                            GestureController.swipe(
                                svc,
                                swipeCoords.startX.toFloat(),
                                swipeCoords.startY.toFloat(),
                                swipeCoords.endX.toFloat(),
                                swipeCoords.endY.toFloat(),
                                480L
                            )
                        }
                        if (a11yOk) {
                            true to "광폭 스와이프(${swipeCoords.startX},${swipeCoords.startY} -> ${swipeCoords.endX},${swipeCoords.endY}) 단일 재실행"
                        } else {
                            val cliOk = withTemporaryHudPassThrough {
                                execOsCliFallback(
                                    "swipe ${swipeCoords.startX} ${swipeCoords.startY} ${swipeCoords.endX} ${swipeCoords.endY} 480"
                                )
                            }
                            cliOk to if (cliOk) {
                                "A11y 스와이프 실패로 OS CLI 광폭 스와이프 폴백 재실행"
                            } else {
                                "광폭 스와이프 재실행 실패"
                            }
                        }
                    } else {
                        val cliOk = withTemporaryHudPassThrough {
                            execOsCliFallback(
                                "swipe ${swipeCoords.startX} ${swipeCoords.startY} ${swipeCoords.endX} ${swipeCoords.endY} 480"
                            )
                        }
                        cliOk to if (cliOk) {
                            "OS CLI 광폭 스와이프(${swipeCoords.startX},${swipeCoords.startY} -> ${swipeCoords.endX},${swipeCoords.endY}) 재실행"
                        } else {
                            "OS CLI 광폭 스와이프 실패"
                        }
                    }
                }
            }

            FailureCategory.APP_LAUNCH_STALLED -> {
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                true to "앱 콜드 스타트 대기(500ms) 후 포그라운드 재검증"
            }

            FailureCategory.NONE -> true to "정상"
        }
    }

    private fun resolveCoordinates(args: JSONObject, snapshot: AtomicSnapshot): Pair<Int, Int>? {
        if (args.has("element_index")) {
            val idx = args.optInt("element_index", -1)
            if (idx >= 0) {
                val el = snapshot.indexedElements.firstOrNull { it.index == idx }
                if (el != null) return el.centerX to el.centerY
            }
        }
        if (args.has("norm_x") && args.has("norm_y")) {
            val nx = args.optInt("norm_x", -1)
            val ny = args.optInt("norm_y", -1)
            if (nx >= 0 && ny >= 0) {
                return CoordinateNormalizer.normalizedToPixel(
                    nx,
                    ny,
                    snapshot.screenWidth,
                    snapshot.screenHeight
                )
            }
        }
        if (args.has("x") && args.has("y")) {
            val rx = args.optInt("x", -1)
            val ry = args.optInt("y", -1)
            if (rx >= 0 && ry >= 0) {
                return if (rx <= 1000 && ry <= 1000) {
                    CoordinateNormalizer.normalizedToPixel(rx, ry, snapshot.screenWidth, snapshot.screenHeight)
                } else {
                    rx.coerceIn(0, (snapshot.screenWidth - 1).coerceAtLeast(0)) to
                        ry.coerceIn(0, (snapshot.screenHeight - 1).coerceAtLeast(0))
                }
            }
        }
        return null
    }

    private fun execOsCliFallback(cmd: String): Boolean {
        return try {
            val outcome = AgentToolExecutionHarness(context).execute(
                "os_cli_exec",
                JSONObject().put("command", cmd),
                null
            )
            outcome.succeeded
        } catch (_: Exception) {
            false
        }
    }
}
