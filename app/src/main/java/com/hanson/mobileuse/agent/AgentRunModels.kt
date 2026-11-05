package com.hanson.mobileuse.agent

import org.json.JSONObject

enum class SubGoalStatus(val marker: String) {
    PENDING("[ ]"),
    IN_PROGRESS("[/]"),
    COMPLETED("[x]")
}

data class SubGoalItem(
    val title: String,
    val status: SubGoalStatus = SubGoalStatus.PENDING
)

data class AgentStepLog(
    val stepNumber: Int,
    val observation: String,
    val planLedger: List<SubGoalItem>,
    val toolName: String,
    val toolArgsJson: String,
    val resultSummary: String,
    val succeeded: Boolean,
    val activePackage: String = "",
    val elementCount: Int = 0,
    val screenshotBase64: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * EZmap Reversibility Receipt ('에이전트가 수행한 작업')
 */
data class ActionReceipt(
    val id: String = "rcpt_${System.currentTimeMillis()}",
    val timestampMs: Long = System.currentTimeMillis(),
    val toolName: String,
    val summaryKo: String,
    val detailKo: String,
    val undoHintKo: String
)

data class PendingApprovalRequest(
    val toolSpec: ToolSpec,
    val args: JSONObject,
    val reasonKo: String
)

enum class AgentRunStatus(val labelKo: String) {
    IDLE("대기 중"),
    OBSERVING("화면 및 UI 트리 분석 중"),
    THINKING("Gemini 멀티모달 추론 중"),
    AWAITING_APPROVAL("사용자 승인 대기 중"),
    ACTING("모바일 액션 실행 중"),
    COMPLETED("작업 완료"),
    STOPPED("사용자 중단"),
    ERROR("오류 발생")
}

data class AgentRunSession(
    val runId: String = "run_${System.currentTimeMillis()}",
    val goal: String = "",
    val status: AgentRunStatus = AgentRunStatus.IDLE,
    val currentIteration: Int = 0,
    val maxIterations: Int = 15,
    val subGoals: List<SubGoalItem> = emptyList(),
    val steps: List<AgentStepLog> = emptyList(),
    val receipts: List<ActionReceipt> = emptyList(),
    val pendingApproval: PendingApprovalRequest? = null,
    val finalSummary: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null
)

sealed class SafetyGateDecision {
    data object Allow : SafetyGateDecision()
    data class AskUser(val reasonKo: String) : SafetyGateDecision()
    data class Deny(val reasonKo: String) : SafetyGateDecision()
}

/**
 * Unified Tool Safety Gate combining EZmap's 3-tier governance with Hanson's
 * Unrestricted Autonomous Mode toggle.
 */
object ToolSafetyGate {

    fun evaluate(
        toolSpec: ToolSpec,
        args: JSONObject,
        permissionHub: PermissionHubManager
    ): SafetyGateDecision {
        // 1. Safe read-only tools are always allowed
        if (toolSpec.riskTier == ToolRiskTier.SAFE) {
            return SafetyGateDecision.Allow
        }

        // 2. If Unrestricted Autonomous Mode is enabled, allow all tools immediately
        if (permissionHub.isUnrestrictedMode()) {
            return SafetyGateDecision.Allow
        }

        // 3. Otherwise, inspect per-capability governance setting
        val capability = toolSpec.capability ?: return SafetyGateDecision.Allow
        return when (permissionHub.getCapabilityLevel(capability)) {
            PermissionLevel.ALWAYS -> SafetyGateDecision.Allow
            PermissionLevel.ASK_EVERY_TIME -> {
                val preview = args.toString().take(120)
                SafetyGateDecision.AskUser(
                    "${capability.displayName} 권한이 '실행 전 확인'으로 설정되어 있습니다.\n요청 도구: ${toolSpec.name} ($preview)"
                )
            }
            PermissionLevel.NEVER -> {
                SafetyGateDecision.Deny(
                    "${capability.displayName} 기능이 거버넌스 설정에서 '차단'되어 있습니다."
                )
            }
        }
    }
}
