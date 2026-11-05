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

enum class ChatSender {
    USER,
    AGENT,
    ACTION,
    NOTIFICATION
}

/**
 * Continuous Runtime Live Chat Message
 */
data class LiveChatMessage(
    val id: String = "msg_${System.nanoTime()}",
    val sender: ChatSender,
    val text: String,
    val subtitle: String = "",
    val toolBadge: String? = null,
    val succeeded: Boolean = true,
    val replySessionId: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sender", sender.name)
        put("text", text)
        put("subtitle", subtitle)
        if (toolBadge != null) put("toolBadge", toolBadge)
        put("succeeded", succeeded)
        if (replySessionId != null) put("replySessionId", replySessionId)
        put("timestampMs", timestampMs)
    }
}

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
    IDLE("라이브 대기 중"),
    OBSERVING("화면 및 UI 트리 분석 중"),
    THINKING("Gemini 멀티모달 추론 중"),
    AWAITING_APPROVAL("사용자 승인 대기 중"),
    ACTING("모바일 액션 실행 중"),
    COMPLETED("라이브 응답 완료"),
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
        if (toolSpec.riskTier == ToolRiskTier.SAFE) {
            return SafetyGateDecision.Allow
        }
        if (permissionHub.isUnrestrictedMode()) {
            return SafetyGateDecision.Allow
        }
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
