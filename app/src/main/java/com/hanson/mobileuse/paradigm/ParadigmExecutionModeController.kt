package com.hanson.mobileuse.paradigm

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.agent.ModelTurnDecision
import com.hanson.mobileuse.agent.SubGoalItem
import com.hanson.mobileuse.agent.SubGoalStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Execution Mode options for multi-step UI navigation and verification.
 */
enum class CompositeExecutionMode(val labelKo: String) {
    RECURSIVE_EVOLUTION_3PHASE("관측-행동-사후검증 표준 루프"),
    SPECULATIVE_PARALLEL("병렬 실행 및 비동기 검증"),
    PRE_SIMULATION_ROLLOUT("상태 그래프 기반 최단 경로 탐색");

    companion object {
        val THREE_PHASE_EVOLUTION = RECURSIVE_EVOLUTION_3PHASE
        val PARALLEL_SPECULATIVE = SPECULATIVE_PARALLEL
    }
}

/**
 * Turn evaluation result determining whether an exact verified shortcut applies
 * or whether full VLM / planner deliberation is invoked.
 */
sealed class ModeTurnResult {
    data class DirectAction(
        val decision: ModelTurnDecision,
        val mode: CompositeExecutionMode,
        val latencyMs: Long,
        val rationaleKo: String
    ) : ModeTurnResult()

    data class EscalateToSystem2(
        val reasonKo: String,
        val expectedGain: Float,
        val s2Cost: Float,
        val candidateDecision: ModelTurnDecision? = null
    ) : ModeTurnResult()

    data class SpeculativeDispatched(
        val speculativeDecision: ModelTurnDecision,
        val verificationSnapshotBefore: AtomicSnapshot,
        val timeoutMs: Long = 1200L
    ) : ModeTurnResult()
}

/**
 * Recorded verified transition step within an episode.
 */
data class VerifiedStepRecord(
    val stepNumber: Int,
    val stateHashBefore: String,
    val stateHashAfter: String,
    val actorTier: String,
    val toolName: String,
    val toolArgsJson: String,
    val verifiedSuccess: Boolean,
    val uiDeltaRatio: Float,
    val latencyMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stepNumber", stepNumber)
        put("stateHashBefore", stateHashBefore)
        put("stateHashAfter", stateHashAfter)
        put("actorTier", actorTier)
        put("toolName", toolName)
        put("toolArgsJson", toolArgsJson)
        put("verifiedSuccess", verifiedSuccess)
        put("uiDeltaRatio", uiDeltaRatio.toDouble())
        put("latencyMs", latencyMs)
    }
}

/**
 * Exact verified action cache entry keyed by `(packageName, exactNormalizedGoal, stateHash)`.
 * Never generalizes across distinct search queries or intents so VLM grounding stays accurate.
 */
data class VerifiedActionShortcut(
    val shortcutKey: String,
    val stateHash: String,
    val toolName: String,
    val toolArgsJson: String,
    var verifiedCount: Int = 1,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("shortcutKey", shortcutKey)
        put("stateHash", stateHash)
        put("toolName", toolName)
        put("toolArgsJson", toolArgsJson)
        put("verifiedCount", verifiedCount)
        put("updatedAtMs", updatedAtMs)
    }
}

/**
 * Lightweight consecutive-step stability tracker for detecting UI transition failures or loops.
 */
class TransitionStabilityTracker {
    var consecutiveVerifiedSteps: Int = 0
        private set
    var stabilityScore: Float = 1.0f
        private set

    fun recordOutcome(verifiedSuccess: Boolean): Float {
        if (verifiedSuccess) {
            consecutiveVerifiedSteps++
            stabilityScore = (0.75f + 0.05f * consecutiveVerifiedSteps).coerceAtMost(0.99f)
        } else {
            consecutiveVerifiedSteps = 0
            stabilityScore = 0.20f
        }
        return stabilityScore
    }

    fun reset() {
        consecutiveVerifiedSteps = 0
        stabilityScore = 1.0f
    }
}

/**
 * Verified Trajectory & Loop-Guard Controller (`ParadigmExecutionModeController`).
 *
 * Tracks verified UI state transitions on `WorldModelGraph`, guards against dead-end state loops,
 * and escalates novel or failed UI screens directly to the Multimodal VLM.
 */
class ParadigmExecutionModeController(
    val worldModelGraph: WorldModelGraph,
    context: Context? = null
) {
    companion object {
        private const val PREFS_NAME = "hanson_verified_trajectory_ledger"
        private const val KEY_SHORTCUTS = "verified_shortcuts_json"

        /**
         * Prunes state revisit cycles from an exploratory trajectory (`S0 -> S1 -> S2 -> S1 -> S3` => `S0 -> S1 -> S3`)
         * and computes step weights from start to goal.
         */
        fun computeLoopErasedTrajectoryWeights(
            stateSequence: List<String>,
            finalOutcomeSuccess: Boolean,
            decay: Float = 0.85f
        ): Pair<List<String>, List<Float>> {
            if (stateSequence.isEmpty()) return emptyList<String>() to emptyList()

            val cleanStates = mutableListOf<String>()
            val seenIndices = mutableMapOf<String, Int>()

            for (state in stateSequence) {
                val prevIdx = seenIndices[state]
                if (prevIdx != null) {
                    while (cleanStates.size > prevIdx) {
                        val removed = cleanStates.removeAt(cleanStates.lastIndex)
                        seenIndices.remove(removed)
                    }
                }
                seenIndices[state] = cleanStates.size
                cleanStates.add(state)
            }

            val sign = if (finalOutcomeSuccess) 1.0f else -0.6f
            val cleanSize = cleanStates.size
            val weights = cleanStates.mapIndexed { idx, _ ->
                val stepsFromEnd = (cleanSize - 1 - idx).coerceAtLeast(0)
                var factor = 1.0f
                repeat(stepsFromEnd) { factor *= decay }
                factor * sign
            }
            return cleanStates to weights
        }
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val stabilityTracker = TransitionStabilityTracker()
    private val verifiedShortcuts = LinkedHashMap<String, VerifiedActionShortcut>()
    private val episodeRecords = mutableListOf<VerifiedStepRecord>()

    var totalShortcutHits: Int = 0
        private set
    var totalVlmEscalations: Int = 0
        private set
    var totalSelfHealInterventions: Int = 0
        private set

    init {
        loadFromDisk()
    }

    fun evaluateTurn(
        snapshot: AtomicSnapshot,
        goal: String,
        mode: CompositeExecutionMode = CompositeExecutionMode.RECURSIVE_EVOLUTION_3PHASE,
        recentStepFailed: Boolean = false
    ): ModeTurnResult {
        val stability = stabilityTracker.recordOutcome(!recentStepFailed)
        val stateHash = WorldModelGraph.computeStateHash(snapshot)

        if (recentStepFailed || stability < 0.35f) {
            totalVlmEscalations++
            return ModeTurnResult.EscalateToSystem2(
                reasonKo = "직전 스텝 화면 전환 미확인 또는 상태 급변 감지 → 멀티모달 VLM 재분석",
                expectedGain = 0.80f,
                s2Cost = 0.20f
            )
        }

        val key = buildExactShortcutKey(snapshot.activePackage, goal, stateHash)
        val shortcut = verifiedShortcuts[key]
        if (shortcut != null && shortcut.verifiedCount >= 2) {
            totalShortcutHits++
            val args = runCatching { JSONObject(shortcut.toolArgsJson) }.getOrDefault(JSONObject())
            val decision = ModelTurnDecision(
                observation = "검증된 동일 화면 단축 경로 실행 (${shortcut.toolName})",
                subGoals = listOf(SubGoalItem("검증 단축 액션 실행 (${shortcut.toolName})", SubGoalStatus.IN_PROGRESS)),
                toolName = shortcut.toolName,
                toolArgs = args
            )
            return when (mode) {
                CompositeExecutionMode.SPECULATIVE_PARALLEL -> ModeTurnResult.SpeculativeDispatched(
                    speculativeDecision = decision,
                    verificationSnapshotBefore = snapshot
                )
                else -> ModeTurnResult.DirectAction(
                    decision = decision,
                    mode = mode,
                    latencyMs = 5L,
                    rationaleKo = "검증된 단축 경로 즉시 실행"
                )
            }
        }

        totalVlmEscalations++
        return ModeTurnResult.EscalateToSystem2(
            reasonKo = "새 화면 상태($stateHash) → 멀티모달 VLM 시각 그라운딩 실행",
            expectedGain = 0.65f,
            s2Cost = 0.25f
        )
    }

    @Synchronized
    fun recordStepTransition(
        stepNumber: Int,
        preSnapshot: AtomicSnapshot,
        postSnapshot: AtomicSnapshot,
        actorTier: String,
        toolName: String,
        toolArgsJson: String,
        verifiedSuccess: Boolean,
        uiDeltaRatio: Float,
        recoveryAttempted: Boolean,
        latencyMs: Long,
        goal: String = ""
    ) {
        if (recoveryAttempted) totalSelfHealInterventions++
        val preHash = WorldModelGraph.computeStateHash(preSnapshot)
        val postHash = WorldModelGraph.computeStateHash(postSnapshot)
        episodeRecords.add(
            VerifiedStepRecord(
                stepNumber = stepNumber,
                stateHashBefore = preHash,
                stateHashAfter = postHash,
                actorTier = actorTier,
                toolName = toolName,
                toolArgsJson = toolArgsJson,
                verifiedSuccess = verifiedSuccess,
                uiDeltaRatio = uiDeltaRatio,
                latencyMs = latencyMs
            )
        )
        if (verifiedSuccess && goal.isNotBlank() && toolName in setOf("click", "input_text", "swipe", "press_key", "manage_app")) {
            val key = buildExactShortcutKey(preSnapshot.activePackage, goal, preHash)
            val prev = verifiedShortcuts[key]
            verifiedShortcuts[key] = VerifiedActionShortcut(
                shortcutKey = key,
                stateHash = preHash,
                toolName = toolName,
                toolArgsJson = toolArgsJson,
                verifiedCount = (prev?.verifiedCount ?: 0) + 1
            )
            persistToDisk()
        }
    }

    @Synchronized
    fun finalizeEpisode(episodeGoalReached: Boolean): List<VerifiedStepRecord> {
        val copy = episodeRecords.toList()
        if (!episodeGoalReached) {
            // Do not keep unverified shortcuts from failed episodes
            for (rec in copy) {
                if (!rec.verifiedSuccess) {
                    verifiedShortcuts.entries.removeIf { it.value.stateHash == rec.stateHashBefore }
                }
            }
        }
        episodeRecords.clear()
        persistToDisk()
        return copy
    }

    @Synchronized
    fun getStatsJson(): JSONObject = JSONObject().apply {
        put("totalShortcutHits", totalShortcutHits)
        put("totalVlmEscalations", totalVlmEscalations)
        put("totalSelfHealInterventions", totalSelfHealInterventions)
        put("verifiedShortcutCount", verifiedShortcuts.size)
        put("consecutiveVerifiedSteps", stabilityTracker.consecutiveVerifiedSteps)
        put("stabilityScore", stabilityTracker.stabilityScore.toDouble())
    }

    private fun buildExactShortcutKey(pkg: String, goal: String, stateHash: String): String {
        val normGoal = goal.trim().lowercase().replace(Regex("\\s+"), " ")
        return "${pkg.substringAfterLast('.')}|$normGoal|$stateHash"
    }

    private fun loadFromDisk() {
        val p = prefs ?: return
        try {
            val raw = p.getString(KEY_SHORTCUTS, null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("shortcutKey")
                if (key.isNotBlank()) {
                    verifiedShortcuts[key] = VerifiedActionShortcut(
                        shortcutKey = key,
                        stateHash = o.optString("stateHash"),
                        toolName = o.optString("toolName"),
                        toolArgsJson = o.optString("toolArgsJson", "{}"),
                        verifiedCount = o.optInt("verifiedCount", 1),
                        updatedAtMs = o.optLong("updatedAtMs", 0L)
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistToDisk() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            verifiedShortcuts.values.toList().takeLast(80).forEach { arr.put(it.toJson()) }
            p.edit().putString(KEY_SHORTCUTS, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
