package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse. accessibility.HierarchyDumper
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentEngineConfig(
    val apiKey: String = "",
    val modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
    val maxIterations: Int = 15,
    val autoShowFloatingHud: Boolean = true
)

/**
 * Core Multimodal Mobile-Use Agent Engine.
 * Orchestrates Perception (AtomicSnapshot) -> Gemini 3-Phase Reasoning -> Safety Gate -> Tool Execution
 * -> Action Receipts & Floating HUD updates.
 */
class MobileUseAgentEngine(
    private val context: Context,
    val permissionHub: PermissionHubManager
) {
    companion object {
        private const val CONFIG_PREFS = "hanson_agent_engine_config"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model_name"
        private const val KEY_MAX_ITER = "max_iterations"
        private const val KEY_AUTO_HUD = "auto_floating_hud"
    }

    private val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val multimodalClient = GeminiMultimodalClient()
    private val toolHarness = AgentToolExecutionHarness(context)

    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AgentEngineConfig> = _config.asStateFlow()

    private val _currentSession = MutableStateFlow(AgentRunSession())
    val currentSession: StateFlow<AgentRunSession> = _currentSession.asStateFlow()

    private val _runHistory = MutableStateFlow<List<AgentRunSession>>(emptyList())
    val runHistory: StateFlow<List<AgentRunSession>> = _runHistory.asStateFlow()

    private val _allReceipts = MutableStateFlow<List<ActionReceipt>>(emptyList())
    val allReceipts: StateFlow<List<ActionReceipt>> = _allReceipts.asStateFlow()

    init {
        HansonForegroundService.onEmergencyStopRequested = { stopRun("알림바 긴급 정지 버튼으로 중단되었습니다.") }
        FloatingHudService.onStopClicked = { stopRun("플로팅 HUD 정지 버튼으로 중단되었습니다.") }
    }

    private fun loadConfig(): AgentEngineConfig {
        return AgentEngineConfig(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            modelName = prefs.getString(KEY_MODEL, GeminiMultimodalClient.DEFAULT_MODEL)
                ?: GeminiMultimodalClient.DEFAULT_MODEL,
            maxIterations = prefs.getInt(KEY_MAX_ITER, 15).coerceIn(3, 40),
            autoShowFloatingHud = prefs.getBoolean(KEY_AUTO_HUD, true)
        )
    }

    fun updateConfig(
        apiKey: String? = null,
        modelName: String? = null,
        maxIterations: Int? = null,
        autoShowFloatingHud: Boolean? = null
    ) {
        val current = _config.value
        val next = current.copy(
            apiKey = apiKey ?: current.apiKey,
            modelName = modelName ?: current.modelName,
            maxIterations = (maxIterations ?: current.maxIterations).coerceIn(3, 40),
            autoShowFloatingHud = autoShowFloatingHud ?: current.autoShowFloatingHud
        )
        prefs.edit()
            .putString(KEY_API_KEY, next.apiKey)
            .putString(KEY_MODEL, next.modelName)
            .putInt(KEY_MAX_ITER, next.maxIterations)
            .putBoolean(KEY_AUTO_HUD, next.autoShowFloatingHud)
            .apply()
        _config.value = next
    }

    fun startRun(goalPrompt: String) {
        val trimmedGoal = goalPrompt.trim()
        if (trimmedGoal.isEmpty()) return

        activeJob?.cancel()
        approvalDeferred?.complete(false)

        val cfg = _config.value
        val initialSession = AgentRunSession(
            goal = trimmedGoal,
            status = AgentRunStatus.OBSERVING,
            maxIterations = cfg.maxIterations,
            subGoals = listOf(SubGoalItem("화면 분석 및 실행 계획 수립", SubGoalStatus.IN_PROGRESS))
        )
        _currentSession.value = initialSession

        if (cfg.autoShowFloatingHud) {
            FloatingHudService.startIfPermitted(context)
        }
        FloatingHudService.updateHudStatus("Hanson AI 실행 중", trimmedGoal.take(32), true)

        activeJob = scope.launch {
            runLoop(trimmedGoal, cfg)
        }
    }

    fun stopRun(reason: String = "사용자 요청으로 에이전트 실행을 중지했습니다.") {
        activeJob?.cancel()
        activeJob = null
        approvalDeferred?.complete(false)
        approvalDeferred = null

        _currentSession.update { prev ->
            val stopped = prev.copy(
                status = AgentRunStatus.STOPPED,
                pendingApproval = null,
                finalSummary = reason,
                finishedAtMs = System.currentTimeMillis()
            )
            archiveSession(stopped)
            stopped
        }
        FloatingHudService.updateHudStatus("에이전트 대기 중", reason.take(30), false)
    }

    fun resolvePendingApproval(approved: Boolean, alwaysAllowCapability: Boolean = false) {
        val pending = _currentSession.value.pendingApproval
        if (approved && alwaysAllowCapability && pending?.toolSpec?.capability != null) {
            permissionHub.setCapabilityLevel(pending.toolSpec.capability, PermissionLevel.ALWAYS)
        }
        approvalDeferred?.complete(approved)
    }

    fun clearReceipts() {
        _allReceipts.value = emptyList()
    }

    private suspend fun runLoop(goal: String, cfg: AgentEngineConfig) {
        val (fallbackW, fallbackH) = HierarchyDumper.getScreenDimensions(context)

        for (iter in 1..cfg.maxIterations) {
            _currentSession.update {
                it.copy(
                    currentIteration = iter,
                    status = AgentRunStatus.OBSERVING
                )
            }
            FloatingHudService.updateHudStatus("Step $iter/${cfg.maxIterations} · 화면 분석", goal.take(30), true)

            // 1. Capture Atomic Snapshot (Screenshot + Multi-Window UI Tree)
            val snapshot: AtomicSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = true
            ) ?: AtomicSnapshot(
                screenWidth = fallbackW,
                screenHeight = fallbackH,
                activePackage = context.packageName,
                activeWindowTitle = "AccessibilityService Off (Fast-Lane Mode)"
            )

            // 2. Decide next action via Gemini VLM + Function Calling (or local fallback)
            _currentSession.update { it.copy(status = AgentRunStatus.THINKING) }
            FloatingHudService.updateHudStatus("Step $iter · AI 추론 중", "화면 요소 ${snapshot.indexedElements.size}개 분석", true)

            val decision = multimodalClient.decideNextAction(
                apiKey = cfg.apiKey,
                modelName = cfg.modelName,
                goal = goal,
                iteration = iter,
                snapshot = snapshot,
                historySteps = _currentSession.value.steps,
                previousPlan = _currentSession.value.subGoals
            )

            _currentSession.update {
                it.copy(subGoals = decision.subGoals)
            }

            // 3. Check if task completed or user question
            if (decision.toolName == "complete_task") {
                val summary = decision.toolArgs.optString("summary", "작업을 성공적으로 완료했습니다.")
                val finalStep = AgentStepLog(
                    stepNumber = iter,
                    observation = decision.observation,
                    planLedger = decision.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgsJson = decision.toolArgs.toString(),
                    resultSummary = summary,
                    succeeded = true,
                    activePackage = snapshot.activePackage,
                    elementCount = snapshot.indexedElements.size,
                    screenshotBase64 = snapshot.screenshotBase64Jpeg
                )
                _currentSession.update { prev ->
                    val completed = prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        subGoals = prev.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                        steps = prev.steps + finalStep,
                        finalSummary = summary,
                        finishedAtMs = System.currentTimeMillis()
                    )
                    archiveSession(completed)
                    completed
                }
                FloatingHudService.updateHudStatus("작업 완료", summary.take(32), false)
                return
            }

            // 4. Safety Gate evaluation
            val spec = ToolSpecRegistry.find(decision.toolName)
            if (spec != null) {
                when (val gate = ToolSafetyGate.evaluate(spec, decision.toolArgs, permissionHub)) {
                    is SafetyGateDecision.Allow -> Unit
                    is SafetyGateDecision.Deny -> {
                        val deniedStep = AgentStepLog(
                            stepNumber = iter,
                            observation = decision.observation,
                            planLedger = decision.subGoals,
                            toolName = decision.toolName,
                            toolArgsJson = decision.toolArgs.toString(),
                            resultSummary = "차단됨: ${gate.reasonKo}",
                            succeeded = false,
                            activePackage = snapshot.activePackage,
                            elementCount = snapshot.indexedElements.size
                        )
                        _currentSession.update { prev ->
                            val stopped = prev.copy(
                                status = AgentRunStatus.STOPPED,
                                steps = prev.steps + deniedStep,
                                finalSummary = gate.reasonKo,
                                finishedAtMs = System.currentTimeMillis()
                            )
                            archiveSession(stopped)
                            stopped
                        }
                        FloatingHudService.updateHudStatus("거버넌스 차단", gate.reasonKo.take(28), false)
                        return
                    }
                    is SafetyGateDecision.AskUser -> {
                        val deferred = CompletableDeferred<Boolean>()
                        approvalDeferred = deferred
                        _currentSession.update {
                            it.copy(
                                status = AgentRunStatus.AWAITING_APPROVAL,
                                pendingApproval = PendingApprovalRequest(
                                    toolSpec = spec,
                                    args = decision.toolArgs,
                                    reasonKo = gate.reasonKo
                                )
                            )
                        }
                        FloatingHudService.updateHudStatus("승인 대기 중", "${spec.name} 실행 확인 필요", true)
                        val approved = deferred.await()
                        approvalDeferred = null
                        _currentSession.update { it.copy(pendingApproval = null) }
                        if (!approved) {
                            stopRun("사용자가 '${spec.name}' 실행을 거부하여 중단했습니다.")
                            return
                        }
                    }
                }
            }

            // 5. Execute Tool
            _currentSession.update { it.copy(status = AgentRunStatus.ACTING) }
            FloatingHudService.updateHudStatus("Step $iter · ${decision.toolName}", decision.observation.take(30), true)

            val outcome = toolHarness.execute(
                toolName = decision.toolName,
                args = decision.toolArgs,
                latestSnapshot = snapshot
            )

            val stepLog = AgentStepLog(
                stepNumber = iter,
                observation = decision.observation,
                planLedger = decision.subGoals,
                toolName = decision.toolName,
                toolArgsJson = decision.toolArgs.toString(),
                resultSummary = outcome.outputText,
                succeeded = outcome.succeeded,
                activePackage = snapshot.activePackage,
                elementCount = snapshot.indexedElements.size,
                screenshotBase64 = snapshot.screenshotBase64Jpeg
            )

            if (outcome.receipt != null) {
                _allReceipts.update { (listOf(outcome.receipt) + it).take(50) }
            }

            _currentSession.update { prev ->
                prev.copy(
                    steps = prev.steps + stepLog,
                    receipts = if (outcome.receipt != null) prev.receipts + outcome.receipt else prev.receipts
                )
            }

            if (decision.toolName == "ask_user") {
                val question = decision.toolArgs.optString("question", "추후 진행 방향을 알려주세요.")
                _currentSession.update { prev ->
                    val finished = prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        finalSummary = "에이전트 질문: $question",
                        finishedAtMs = System.currentTimeMillis()
                    )
                    archiveSession(finished)
                    finished
                }
                FloatingHudService.updateHudStatus("질문 대기", question.take(30), false)
                return
            }

            // Settle UI animations before next perception snapshot
            delay(900L)
        }

        // Max iterations reached
        _currentSession.update { prev ->
            val finished = prev.copy(
                status = AgentRunStatus.COMPLETED,
                finalSummary = "최대 반복 횟수(${cfg.maxIterations}회)에 도달하여 실행을 마무리했습니다.",
                finishedAtMs = System.currentTimeMillis()
            )
            archiveSession(finished)
            finished
        }
        FloatingHudService.updateHudStatus("반복 완료", "최대 스텝 도달", false)
    }

    private fun archiveSession(session: AgentRunSession) {
        _runHistory.update { prev ->
            (listOf(session) + prev.filterNot { it.runId == session.runId }).take(25)
        }
    }
}
