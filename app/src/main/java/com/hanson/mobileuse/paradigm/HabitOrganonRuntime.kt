package com.hanson.mobileuse.paradigm

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.agent.ModelTurnDecision
import com.hanson.mobileuse.agent.SubGoalItem
import com.hanson.mobileuse.agent.SubGoalStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * Execution Mode combining the 3 strategies chosen by the user in Grill-Me #3 & #4:
 * 1. MODEL_BASED_ROLLOUT: Lookahead over WorldModelGraph to pick the highest expected verified value trajectory.
 * 2. SPECULATIVE_S1_ASYNC_S2: Fast S1 Habit/Prototype reflex executes immediately while S2 monitors asynchronously.
 * 3. RECURSIVE_HARNESS2_DISTILLATION: S2 Visual/CLI Judge resolves escalations and distills loop-erased trajectories into S1.
 */
enum class ParadigmExecutionMode(val labelKo: String) {
    HYBRID_ALL_THREE("통합 모드: S1/S2 Habit 위임 + Model-Based Rollout + 투기적 실행 + 지연 신용 할당")
}

/**
 * Evidence-Based Deferral Decision (`Avalokita Habit` `escalate` + `Organon` Paired-Evidence Gate).
 * Rather than relying on raw confidence/entropy, compares Expected Verified Gain of calling S2
 * against S2 Call Cost & Recovery Risk.
 */
data class DeferralGateDecision(
    val escalateToS2: Boolean,
    val expectedS1SuccessProb: Float,
    val expectedS2Gain: Float,
    val s2CallCostUnits: Float,
    val reasonKo: String,
    val recommendedS1Decision: ModelTurnDecision? = null
)

/**
 * Provenance & Lifecycle Cost Record (`Organon` + `RevisionLab` Delayed-Feedback Memory Contract).
 */
data class EpisodeProvenanceRecord(
    val stepNumber: Int,
    val stateHashBefore: String,
    val stateHashAfter: String,
    val actorTier: String, // "S1_HABIT_PROTOTYPE", "S1_ROLLOUT", "S2_DELIBERATIVE", "SELF_HEALER"
    val toolName: String,
    val toolArgsJson: String,
    val immediateVerifiedSuccess: Boolean,
    val uiDeltaRatio: Float,
    val latencyMs: Long,
    var delayedCreditScore: Float = 0f // Updated at episode completion via Delayed Credit Assignment
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stepNumber", stepNumber)
        put("stateHashBefore", stateHashBefore)
        put("stateHashAfter", stateHashAfter)
        put("actorTier", actorTier)
        put("toolName", toolName)
        put("toolArgsJson", toolArgsJson)
        put("immediateVerifiedSuccess", immediateVerifiedSuccess)
        put("uiDeltaRatio", uiDeltaRatio.toDouble())
        put("latencyMs", latencyMs)
        put("delayedCreditScore", delayedCreditScore.toDouble())
    }
}

/**
 * Latest-Per-Slot Prototype Entry (`Organon` 96.43% baseline control + `Habit` fast slot binding).
 */
data class SlotPrototypeBinding(
    val intentSlotKey: String,       // e.g. "com.google.android.youtube|SEARCH_INPUT"
    val stateHash: String,
    val toolName: String,
    val toolArgsTemplateJson: String,
    var cumulativeCredit: Float = 1.0f,
    var uses: Int = 1,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("intentSlotKey", intentSlotKey)
        put("stateHash", stateHash)
        put("toolName", toolName)
        put("toolArgsTemplateJson", toolArgsTemplateJson)
        put("cumulativeCredit", cumulativeCredit.toDouble())
        put("uses", uses)
        put("updatedAtMs", updatedAtMs)
    }
}

/**
 * Core Engine implementing the User's Research Vision across `Avalokita Habit`, `Organon`,
 * `RevisionLab`, and `Google Harness-2`:
 *
 * 1. **Latest-Per-Slot Prototype & Transient Fast State (`Organon`)**:
 *    Binds `(packageName, goalIntent, stateHash)` to verified executable actions within & across episodes.
 * 2. **Model-Based Rollout (`Lookahead` on `WorldModelGraph`)**:
 *    Simulates candidate trajectories $S_t \to S_{t+1} \to S_{t+2}$ on the learned graph before touching the screen.
 * 3. **Evidence-Based Deferral Gate (`Habit` `escalate` contract)**:
 *    Computes $\mathbb{E}[\text{VerifiedGain}(S_2 - S_1)] - \text{Cost}(S_2)$. If $\le 0$, S1 executes in <15ms;
 *    if $> 0$ (novel screen, repeated failure, or negative delayed credit), sets `escalateToS2 = true`.
 * 4. **Delayed Credit Assignment (`RevisionLab` / `ArchCredit`)**:
 *    When an episode finishes (or fails), back-propagates discounted credit $\gamma^{T-t} \cdot R_{\text{final}}$
 *    along the loop-erased trajectory, penalizing dead-end branches and reinforcing verified shortcuts.
 */
class HabitOrganonRuntime(
    context: Context,
    private val worldModelGraph: WorldModelGraph
) {
    companion object {
        private const val PREFS_NAME = "hanson_habit_organon_runtime"
        private const val KEY_PROTOTYPES = "latest_slot_prototypes_json"
        private const val KEY_LIFECYCLE_LEDGER = "lifecycle_ledger_stats_json"
        private const val DISCOUNT_GAMMA = 0.85f
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val slotPrototypes = LinkedHashMap<String, SlotPrototypeBinding>()
    private val currentEpisodeTrace = mutableListOf<EpisodeProvenanceRecord>()

    var totalS1Calls: Int = 0
        private set
    var totalS2Deferrals: Int = 0
        private set
    var totalSelfHealInterventions: Int = 0
        private set

    init {
        loadFromDisk()
    }

    /**
     * Evaluates the Evidence-Based Deferral Gate (`Habit` `escalate` + `Organon` Model-Based Rollout).
     */
    @Synchronized
    fun evaluateDeferralGateAndRollout(
        goal: String,
        snapshot: AtomicSnapshot,
        recentStepFailed: Boolean
    ): DeferralGateDecision {
        val currentHash = WorldModelGraph.computeStateHash(snapshot)
        val slotKey = buildSlotKey(snapshot.activePackage, goal, currentHash)

        // Rule 1: If the immediately preceding step failed verification, escalate to S2 / Healer
        if (recentStepFailed) {
            totalS2Deferrals++
            return DeferralGateDecision(
                escalateToS2 = true,
                expectedS1SuccessProb = 0.15f,
                expectedS2Gain = 0.75f,
                s2CallCostUnits = 0.20f,
                reasonKo = "직전 스텝 상태 전이 검증 실패 감지 → S2 심판 및 자기수복 루프로 즉시 위임(Escalate)"
            )
        }

        // Rule 2: Check Latest-Per-Slot Prototype Memory (Organon 96.43% fast-state control)
        val proto = slotPrototypes[slotKey]
        if (proto != null && proto.cumulativeCredit >= 0.6f) {
            val s1Prob = (1f / (1f + exp(-proto.cumulativeCredit.toDouble()))).toFloat().coerceIn(0.1f, 0.98f)
            val expectedS2Gain = (1.0f - s1Prob)
            val s2Cost = 0.25f
            if (expectedS2Gain < s2Cost) {
                totalS1Calls++
                val argsObj = runCatching { JSONObject(proto.toolArgsTemplateJson) }.getOrDefault(JSONObject())
                return DeferralGateDecision(
                    escalateToS2 = false,
                    expectedS1SuccessProb = s1Prob,
                    expectedS2Gain = expectedS2Gain,
                    s2CallCostUnits = s2Cost,
                    reasonKo = "[S1 Habit Prototype Hit] 검증된 슬롯 프로토타입('$slotKey', 신용도=${"%.2f".format(proto.cumulativeCredit)}) 즉시 실행",
                    recommendedS1Decision = ModelTurnDecision(
                        observation = "[Habit S1 반사 실행 · Escalate=false] 학습된 슬롯 프로토타입($slotKey)을 0-LLM으로 즉시 실행합니다.",
                        subGoals = listOf(SubGoalItem("S1 Habit 프로토타입 실행 (${proto.toolName})", SubGoalStatus.IN_PROGRESS)),
                        toolName = proto.toolName,
                        toolArgs = argsObj
                    )
                )
            }
        }

        // Rule 3: Model-Based Rollout over WorldModelGraph
        val rolloutEdges = worldModelGraph.findShortestTrajectory(currentHash) { node ->
            node.hasEditableField || node.keyAnchors.any { anchor -> goal.contains(anchor, ignoreCase = true) }
        }
        if (rolloutEdges.isNotEmpty()) {
            val firstEdge = rolloutEdges.first()
            if (firstEdge.reliabilityScore >= 0.70f) {
                totalS1Calls++
                val argsObj = runCatching { JSONObject(firstEdge.toolArgsJson) }.getOrDefault(JSONObject())
                return DeferralGateDecision(
                    escalateToS2 = false,
                    expectedS1SuccessProb = firstEdge.reliabilityScore,
                    expectedS2Gain = 1.0f - firstEdge.reliabilityScore,
                    s2CallCostUnits = 0.25f,
                    reasonKo = "[S1 Model-Based Rollout] WorldModelGraph ${rolloutEdges.size}-스텝 선행 예측 성공 (신뢰도 ${(firstEdge.reliabilityScore * 100).toInt()}%)",
                    recommendedS1Decision = ModelTurnDecision(
                        observation = "[WorldModel Rollout · S1] 상태 그래프 최단 경로(${firstEdge.fromStateHash} → ${firstEdge.toStateHash}) 예측에 따라 '${firstEdge.toolName}'을 실행합니다.",
                        subGoals = listOf(SubGoalItem("WorldModel 선행 예측 궤적 실행", SubGoalStatus.IN_PROGRESS)),
                        toolName = firstEdge.toolName,
                        toolArgs = argsObj
                    )
                )
            }
        }

        // Default: Novel state or insufficient paired evidence -> Defer (escalate = true) to S2
        totalS2Deferrals++
        return DeferralGateDecision(
            escalateToS2 = true,
            expectedS1SuccessProb = 0.30f,
            expectedS2Gain = 0.60f,
            s2CallCostUnits = 0.25f,
            reasonKo = "[Deferral Gate · Escalate=true] 미학습 화면 상태($currentHash) → S2 플래너/VLM으로 위임"
        )
    }

    /**
     * Records a step's provenance into the current episode trace.
     */
    @Synchronized
    fun recordStepProvenance(
        stepNumber: Int,
        preSnapshot: AtomicSnapshot,
        postSnapshot: AtomicSnapshot,
        actorTier: String,
        toolName: String,
        toolArgsJson: String,
        verifiedSuccess: Boolean,
        uiDeltaRatio: Float,
        recoveryAttempted: Boolean,
        latencyMs: Long
    ) {
        if (recoveryAttempted) totalSelfHealInterventions++
        val preHash = WorldModelGraph.computeStateHash(preSnapshot)
        val postHash = WorldModelGraph.computeStateHash(postSnapshot)
        currentEpisodeTrace.add(
            EpisodeProvenanceRecord(
                stepNumber = stepNumber,
                stateHashBefore = preHash,
                stateHashAfter = postHash,
                actorTier = actorTier,
                toolName = toolName,
                toolArgsJson = toolArgsJson,
                immediateVerifiedSuccess = verifiedSuccess,
                uiDeltaRatio = uiDeltaRatio,
                latencyMs = latencyMs
            )
        )
    }

    /**
     * RevisionLab / ArchCredit Delayed Credit Assignment & Loop-Erased Skill Consolidation:
     * At the end of a multi-step goal episode, back-propagates final episode success/failure credit
     * across the loop-erased trajectory and updates Latest-Per-Slot Prototypes (`Organon`).
     */
    @Synchronized
    fun finalizeEpisodeAndAssignDelayedCredit(
        goal: String,
        activePackage: String,
        episodeGoalReached: Boolean
    ): List<EpisodeProvenanceRecord> {
        if (currentEpisodeTrace.isEmpty()) return emptyList()

        // 1. Loop-Erased Walk: strip dead-end cycles where stateHashBefore was revisited
        val cleanTrace = mutableListOf<EpisodeProvenanceRecord>()
        val seenIndexByState = mutableMapOf<String, Int>()
        for (rec in currentEpisodeTrace) {
            val prevIdx = seenIndexByState[rec.stateHashBefore]
            if (prevIdx != null) {
                while (cleanTrace.size > prevIdx) {
                    val removed = cleanTrace.removeAt(cleanTrace.lastIndex)
                    seenIndexByState.remove(removed.stateHashBefore)
                }
            }
            seenIndexByState[rec.stateHashBefore] = cleanTrace.size
            cleanTrace.add(rec)
        }

        // 2. Back-propagate discounted delayed credit R_t = gamma^(T - t) * R_final + immediateDeltaBonus
        val finalReward = if (episodeGoalReached) 1.0f else -0.6f
        val totalClean = cleanTrace.size
        cleanTrace.forEachIndexed { idx, rec ->
            val stepsFromEnd = (totalClean - 1 - idx).coerceAtLeast(0)
            var discount = 1.0f
            repeat(stepsFromEnd) { discount *= DISCOUNT_GAMMA }
            val immediateBonus = if (rec.immediateVerifiedSuccess) 0.25f else -0.40f
            val credit = (discount * finalReward) + immediateBonus
            rec.delayedCreditScore = credit

            // 3. Consolidate into Latest-Per-Slot Prototype if positive credit
            if (credit > 0f && rec.immediateVerifiedSuccess) {
                val slotKey = buildSlotKey(activePackage, goal, rec.stateHashBefore)
                val existing = slotPrototypes[slotKey]
                slotPrototypes[slotKey] = SlotPrototypeBinding(
                    intentSlotKey = slotKey,
                    stateHash = rec.stateHashBefore,
                    toolName = rec.toolName,
                    toolArgsTemplateJson = rec.toolArgsJson,
                    cumulativeCredit = ((existing?.cumulativeCredit ?: 0.5f) * 0.7f) + (credit * 0.6f),
                    uses = (existing?.uses ?: 0) + 1
                )
            }
        }

        val finalizedCopy = cleanTrace.toList()
        currentEpisodeTrace.clear()
        persistToDisk()
        return finalizedCopy
    }

    private fun buildSlotKey(pkg: String, goal: String, stateHash: String): String {
        val intentVerb = when {
            goal.contains("검색") -> "SEARCH"
            goal.contains("드래그") || goal.contains("슬라이더") -> "DRAG"
            goal.contains("답장") -> "REPLY"
            else -> "NAV"
        }
        return "${pkg.substringAfterLast('.')}|$intentVerb|$stateHash"
    }

    private fun loadFromDisk() {
        try {
            val raw = prefs.getString(KEY_PROTOTYPES, null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("intentSlotKey")
                    if (key.isNotBlank()) {
                        slotPrototypes[key] = SlotPrototypeBinding(
                            intentSlotKey = key,
                            stateHash = o.optString("stateHash"),
                            toolName = o.optString("toolName"),
                            toolArgsTemplateJson = o.optString("toolArgsTemplateJson"),
                            cumulativeCredit = o.optDouble("cumulativeCredit", 1.0).toFloat(),
                            uses = o.optInt("uses", 1),
                            updatedAtMs = o.optLong("updatedAtMs", 0L)
                        )
                    }
                }
            }
            val statsRaw = prefs.getString(KEY_LIFECYCLE_LEDGER, null)
            if (!statsRaw.isNullOrBlank()) {
                val s = JSONObject(statsRaw)
                totalS1Calls = s.optInt("totalS1Calls", 0)
                totalS2Deferrals = s.optInt("totalS2Deferrals", 0)
                totalSelfHealInterventions = s.optInt("totalSelfHealInterventions", 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun persistToDisk() {
        try {
            val arr = JSONArray()
            slotPrototypes.values.toList().takeLast(100).forEach { arr.put(it.toJson()) }
            val stats = JSONObject().apply {
                put("totalS1Calls", totalS1Calls)
                put("totalS2Deferrals", totalS2Deferrals)
                put("totalSelfHealInterventions", totalSelfHealInterventions)
            }
            prefs.edit()
                .putString(KEY_PROTOTYPES, arr.toString())
                .putString(KEY_LIFECYCLE_LEDGER, stats.toString())
                .apply()
        } catch (_: Exception) {
        }
    }
}
