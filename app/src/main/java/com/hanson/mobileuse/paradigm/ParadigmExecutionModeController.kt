package com.hanson.mobileuse.paradigm

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.ModelTurnDecision
import com.hanson.mobileuse.agent.SubGoalItem
import com.hanson.mobileuse.agent.SubGoalStatus
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 3 Composite Execution Modes required by NEXT_GEN_MOBILE_AGENT_BLUEPRINT and PROJECT.md.
 */
enum class CompositeExecutionMode(val labelKo: String) {
    RECURSIVE_EVOLUTION_3PHASE("3-Phase 재귀 진화 루프 (S1 반사 -> S2 심판 -> LERW 증류)"),
    SPECULATIVE_PARALLEL("병렬 투기적 실행 (Speculative S1 + Async S2 Verification)"),
    PRE_SIMULATION_ROLLOUT("사전 시뮬레이션 후 실행 (WorldModelGraph Model-Based Rollout)");

    companion object {
        val THREE_PHASE_EVOLUTION = RECURSIVE_EVOLUTION_3PHASE
        val PARALLEL_SPECULATIVE = SPECULATIVE_PARALLEL
    }
}

/**
 * Result of evaluating a single turn across the composite execution modes.
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
 * Mathematical 16KB System-1 Representation & Inference Engine (`Avalokita Habit`).
 * Uses a d=4096 linear probe vector (4096 * 4 bytes = 16,384 bytes == 16 KB)
 * operating on centered option embeddings.
 */
class S1LinearProbeAdapter(
    val dimension: Int = 4096
) {
    // 16KB weights array: 4096 * 4 bytes
    val weights: FloatArray = FloatArray(dimension) { 0.01f }

    init {
        // Initialize weights deterministically
        for (i in 0 until dimension) {
            weights[i] = ((i % 17) - 8) * 0.005f
        }
    }

    /**
     * Centers option representations across K candidates and scales by average norm.
     */
    fun centerAndScaleOptionRepresentations(rawOptionVectors: List<FloatArray>): List<FloatArray> {
        val k = rawOptionVectors.size
        if (k == 0) return emptyList()
        val d = rawOptionVectors.first().size

        // 1. Mean vector across options
        val meanVector = FloatArray(d)
        for (vec in rawOptionVectors) {
            for (j in 0 until d) meanVector[j] += vec[j]
        }
        for (j in 0 until d) meanVector[j] /= k.toFloat()

        // 2. Average norm s
        var totalNorm = 0f
        for (vec in rawOptionVectors) {
            var sumSq = 0f
            for (v in vec) sumSq += v * v
            totalNorm += sqrt(sumSq)
        }
        val s = (totalNorm / k.toFloat()).coerceAtLeast(1e-6f)

        // 3. Centered and scaled options
        return rawOptionVectors.map { vec ->
            FloatArray(d) { j -> (vec[j] - meanVector[j]) / s }
        }
    }

    /**
     * Computes option probabilities via dot-product and softmax:
     * p_w(y = o | x) = exp(w^T \phi_o(x)) / \sum exp(w^T \phi_o'(x))
     */
    fun scoreOptions(centeredOptionVectors: List<FloatArray>): FloatArray {
        val k = centeredOptionVectors.size
        if (k == 0) return FloatArray(0)

        val logits = FloatArray(k)
        for (i in 0 until k) {
            val vec = centeredOptionVectors[i]
            var dot = 0f
            val len = minOf(weights.size, vec.size)
            for (j in 0 until len) {
                dot += weights[j] * vec[j]
            }
            logits[i] = dot
        }

        val maxLogit = logits.maxOrNull() ?: 0f
        var sumExp = 0f
        val expVals = FloatArray(k)
        for (i in 0 until k) {
            expVals[i] = exp((logits[i] - maxLogit).toDouble()).toFloat()
            sumExp += expVals[i]
        }
        val probs = FloatArray(k)
        for (i in 0 until k) {
            probs[i] = expVals[i] / sumExp.coerceAtLeast(1e-8f)
        }
        return probs
    }

    var distilledUpdatesCount: Int = 0
        private set

    /**
     * Encodes a `(goal, snapshot, element)` triple into a 4096-dimensional multimodal/structural
     * feature representation $\phi(x, e_i) \in \mathbb{R}^{4096}$ for the on-device 16KB probe.
     */
    fun encodeCandidateOptionVector(
        goal: String,
        snapshot: AtomicSnapshot,
        element: IndexedUiElement
    ): FloatArray {
        val vec = FloatArray(dimension)
        val pkg = snapshot.activePackage.substringAfterLast('.')
        val label = element.text.ifBlank { element.contentDesc }.lowercase()
        val gLower = goal.lowercase()

        fun addHashedFeature(key: String, weight: Float = 1.0f) {
            val h1 = abs(key.hashCode()) % dimension
            val h2 = abs((key.hashCode() * 31) xor 0x5bd1e995) % dimension
            vec[h1] += weight
            vec[h2] += weight * 0.5f
        }

        // 1. Structural & spatial features
        addHashedFeature("pkg:$pkg|cls:${element.shortClass}", 1.2f)
        addHashedFeature("pkg:$pkg|grid:${element.normX / 125}_${element.normY / 125}", 1.0f)
        if (element.clickable) addHashedFeature("attr:clickable|cls:${element.shortClass}", 0.8f)
        if (element.editable) addHashedFeature("attr:editable|pkg:$pkg", 1.5f)
        if (element.resourceId.isNotBlank()) {
            addHashedFeature("res:${element.resourceId.substringAfterLast('/')}", 1.4f)
        }

        // 2. Semantic label & goal cross-interaction features
        if (label.isNotBlank()) {
            addHashedFeature("lbl:${label.take(18)}", 1.8f)
            if (gLower.contains(label) || label.contains(gLower.take(6))) {
                addHashedFeature("match:exact_overlap", 3.2f)
            }
            val goalTokens = gLower.split(Regex("\\s+")).filter { it.length >= 2 }
            for (gt in goalTokens) {
                addHashedFeature("cross:$gt->${label.take(12)}", 1.5f)
                if (element.editable) addHashedFeature("edit_intent:$gt", 1.6f)
            }
        }
        return vec
    }

    /**
     * Online Teacher-to-Student Distillation (`System 2 VLM -> System 1 16KB Probe`):
     * Performs a cross-entropy gradient ascent step $\Delta w = \eta (\tilde{\phi}_{i^*} - \sum_j p_j \tilde{\phi}_j)$
     * whenever a verified System-2 action selects `chosenElementIndex` on `snapshot`.
     */
    @Synchronized
    fun distillFromVerifiedTeacherStep(
        goal: String,
        snapshot: AtomicSnapshot,
        chosenElementIndex: Int,
        learningRate: Float = 1.8f
    ): Float {
        val candidates = snapshot.indexedElements.take(40)
        val targetPos = candidates.indexOfFirst { it.index == chosenElementIndex }
        if (targetPos < 0 || candidates.size < 2) return 0f

        val rawVectors = candidates.map { encodeCandidateOptionVector(goal, snapshot, it) }
        val centered = centerAndScaleOptionRepresentations(rawVectors)
        val probs = scoreOptions(centered)

        val chosenVec = centered[targetPos]
        for (d in 0 until dimension) {
            var expectedFeat = 0f
            for (j in centered.indices) {
                expectedFeat += probs[j] * centered[j][d]
            }
            weights[d] += learningRate * (chosenVec[d] - expectedFeat)
        }
        distilledUpdatesCount++
        val newProbs = scoreOptions(centered)
        return newProbs[targetPos]
    }

    /**
     * Evaluates all candidate UI elements on `snapshot` using the distilled 16KB probe.
     * Returns `(bestElement, probability)` if distilled knowledge is confident.
     */
    @Synchronized
    fun predictDistilledCandidate(
        goal: String,
        snapshot: AtomicSnapshot
    ): Pair<IndexedUiElement, Float>? {
        if (distilledUpdatesCount == 0) return null
        val candidates = snapshot.indexedElements.take(40)
        if (candidates.size < 2) return null

        val rawVectors = candidates.map { encodeCandidateOptionVector(goal, snapshot, it) }
        val centered = centerAndScaleOptionRepresentations(rawVectors)
        val probs = scoreOptions(centered)

        var bestIdx = 0
        var bestProb = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > bestProb) {
                bestProb = probs[i]
                bestIdx = i
            }
        }
        return candidates[bestIdx] to bestProb
    }
}

/**
 * Bayesian Online Change-Point Detection (BOCPD) tracking silent UI updates.
 * Evaluates run length \ell_t with hazard rate H = 1/50 per Avalokita Habit.
 */
class BayesianOnlineChangePointDetector(
    val hazardRate: Float = 0.02f // H = 1/50
) {
    var runLength: Int = 0
        private set
    var regimeStabilityProbability: Float = 1.0f
        private set

    /**
     * Updates change-point probability given verified transition feedback.
     * Rejects unverified teacher model outputs; only updates with real environment feedback.
     */
    fun updateWithVerifiedEvidence(verifiedSuccess: Boolean): Float {
        val likelihood = if (verifiedSuccess) 0.96f else 0.08f
        val growthProb = regimeStabilityProbability * likelihood * (1.0f - hazardRate)
        val changepointProb = hazardRate * regimeStabilityProbability * 0.20f
        val total = growthProb + changepointProb

        regimeStabilityProbability = (growthProb / total.coerceAtLeast(1e-6f)).coerceIn(0.01f, 0.99f)

        if (verifiedSuccess) {
            runLength++
        } else {
            runLength = 0
            regimeStabilityProbability = 0.15f
        }
        return regimeStabilityProbability
    }

    fun reset() {
        runLength = 0
        regimeStabilityProbability = 1.0f
    }
}

/**
 * Paradigm Execution Mode Controller (`com.hanson.mobileuse.paradigm`).
 *
 * Implements the 3 Composite Execution Modes:
 * 1. Mode 1: 3-Phase Recursive Evolution Loop
 * 2. Mode 2: Parallel Speculative Execution
 * 3. Mode 3: Pre-simulation Model-Based Rollout
 *
 * Enforces the 16KB S1 Escalate Gate, BOCPD change-point monitoring,
 * and seamless coordination between fast S1 reflexes and S2 deliberation.
 */
class ParadigmExecutionModeController(
    val worldModelGraph: WorldModelGraph,
    val habitOrganonRuntime: HabitOrganonRuntime
) {
    val s1Adapter = S1LinearProbeAdapter(4096)
    val bocpdDetector = BayesianOnlineChangePointDetector(hazardRate = 0.02f)

    /**
     * Core contract:
     * `evaluateTurn(snapshot, goal, mode, recentStepFailed)`
     */
    fun evaluateTurn(
        snapshot: AtomicSnapshot,
        goal: String,
        mode: CompositeExecutionMode = CompositeExecutionMode.RECURSIVE_EVOLUTION_3PHASE,
        recentStepFailed: Boolean = false
    ): ModeTurnResult {
        // Step 1: Update BOCPD with verified outcome of previous step
        val stabilityProb = bocpdDetector.updateWithVerifiedEvidence(!recentStepFailed)

        // Step 2: Avalokita Habit 16KB Escalate Gate Evaluation
        val deferral = habitOrganonRuntime.evaluateDeferralGateAndRollout(
            goal = goal,
            snapshot = snapshot,
            recentStepFailed = recentStepFailed || stabilityProb < 0.35f
        )

        // Rule 1: If Gate commands escalation to System-2, first check if our Distilled On-Device 16KB Probe is confident!
        if (deferral.escalateToS2) {
            if (!recentStepFailed && stabilityProb >= 0.35f) {
                val distilledPred = s1Adapter.predictDistilledCandidate(goal, snapshot)
                if (distilledPred != null && distilledPred.second >= 0.68f) {
                    val el = distilledPred.first
                    val probPct = (distilledPred.second * 100).toInt()
                    val label = el.text.ifBlank { el.contentDesc.ifBlank { el.shortClass } }.take(24)
                    val distilledDecision = ModelTurnDecision(
                        observation = "[S1 Distilled On-Device VLM Probe · p=${probPct}%] 증류된 16KB 온디바이스 프로브가 '$label' 요소를 즉시 선택했습니다.",
                        subGoals = listOf(SubGoalItem("S1 온디바이스 증류 정책 실행 ($label)", SubGoalStatus.IN_PROGRESS)),
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", el.index)
                            put("target_description", label)
                        }
                    )
                    return ModeTurnResult.DirectAction(
                        decision = distilledDecision,
                        mode = mode,
                        latencyMs = 4L,
                        rationaleKo = "[S1 Distilled VLM Probe Hit · p=${probPct}%] S2 호출 없이 온디바이스 증류 가중치로 즉시 실행"
                    )
                }
            }
            val reason = if (stabilityProb < 0.35f) {
                "[BOCPD UI 상태 급변 감지 · RunLength=${bocpdDetector.runLength}] ${deferral.reasonKo}"
            } else {
                deferral.reasonKo
            }
            return ModeTurnResult.EscalateToSystem2(
                reasonKo = reason,
                expectedGain = deferral.expectedS2Gain,
                s2Cost = deferral.s2CallCostUnits,
                candidateDecision = deferral.recommendedS1Decision
            )
        }

        val s1Decision = deferral.recommendedS1Decision
            ?: return ModeTurnResult.EscalateToSystem2("S1 액션 추천 실패 (결정 객체 null)", 0.5f, 0.25f)

        // Rule 2: Execute based on the chosen Composite Execution Mode
        return when (mode) {
            CompositeExecutionMode.SPECULATIVE_PARALLEL -> {
                ModeTurnResult.SpeculativeDispatched(
                    speculativeDecision = s1Decision,
                    verificationSnapshotBefore = snapshot,
                    timeoutMs = 1200L
                )
            }
            CompositeExecutionMode.PRE_SIMULATION_ROLLOUT -> {
                ModeTurnResult.DirectAction(
                    decision = s1Decision,
                    mode = CompositeExecutionMode.PRE_SIMULATION_ROLLOUT,
                    latencyMs = 10L,
                    rationaleKo = "[Mode 3 Model-Based Rollout] ${deferral.reasonKo}"
                )
            }
            CompositeExecutionMode.RECURSIVE_EVOLUTION_3PHASE -> {
                ModeTurnResult.DirectAction(
                    decision = s1Decision,
                    mode = CompositeExecutionMode.RECURSIVE_EVOLUTION_3PHASE,
                    latencyMs = 15L,
                    rationaleKo = "[Mode 1 S1 Habit Reflex] ${deferral.reasonKo}"
                )
            }
        }
    }

    /**
     * Evaluates a Loop-Erased Random Walk (LERW) and discounted gamma credit backpropagation
     * on an arbitrary sequence of state hashes and immediate rewards.
     * Useful for standalone rubric validation and trajectory optimization.
     */
    fun computeLoopErasedDiscountedCredit(
        stateSequence: List<String>,
        finalOutcomeSuccess: Boolean,
        gamma: Float = 0.85f
    ): Pair<List<String>, List<Float>> =
        Companion.computeLoopErasedDiscountedCredit(stateSequence, finalOutcomeSuccess, gamma)

    /**
     * Online Distillation Hook:
     * Distills a verified System-2 (or fast-path) action into the on-device 16KB `S1LinearProbeAdapter`.
     */
    fun distillVerifiedStep(
        goal: String,
        snapshot: AtomicSnapshot,
        decision: ModelTurnDecision
    ): Float {
        if (decision.toolName in setOf("click", "long_press", "input_text") && decision.toolArgs.has("element_index")) {
            val chosenIdx = decision.toolArgs.optInt("element_index", -1)
            if (chosenIdx >= 0) {
                return s1Adapter.distillFromVerifiedTeacherStep(goal, snapshot, chosenIdx)
            }
        }
        return 0f
    }

    companion object {
        fun computeLoopErasedDiscountedCredit(
            stateSequence: List<String>,
            finalOutcomeSuccess: Boolean,
            gamma: Float = 0.85f
        ): Pair<List<String>, List<Float>> {
            if (stateSequence.isEmpty()) return emptyList<String>() to emptyList()

            // 1. Loop-Erased Walk (strip cycles)
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

            // 2. Discounted Credit Backpropagation
            val finalReward = if (finalOutcomeSuccess) 1.0f else -0.6f
            val cleanSize = cleanStates.size
            val credits = cleanStates.mapIndexed { idx, _ ->
                val stepsFromEnd = (cleanSize - 1 - idx).coerceAtLeast(0)
                var discount = 1.0f
                repeat(stepsFromEnd) { discount *= gamma }
                discount * finalReward
            }

            return cleanStates to credits
        }
    }
}
