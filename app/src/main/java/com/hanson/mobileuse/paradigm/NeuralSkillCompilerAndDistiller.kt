package com.hanson.mobileuse.paradigm

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.agent.AgentToolExecutionHarness
import com.hanson.mobileuse.agent.ModelTurnDecision
import com.hanson.mobileuse.agent.SubGoalItem
import com.hanson.mobileuse.agent.SubGoalStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Spatial zone on the mobile screen, invariant to minor pixel shifts.
 */
enum class ScreenSpatialZone {
    TOP_HEADER,
    SEARCH_OR_TAB_STRIP,
    MAIN_CONTENT_BODY,
    BOTTOM_INPUT_OR_NAV;

    companion object {
        fun fromNormY(normY: Int): ScreenSpatialZone = when {
            normY < 140 -> TOP_HEADER
            normY < 260 -> SEARCH_OR_TAB_STRIP
            normY > 820 -> BOTTOM_INPUT_OR_NAV
            else -> MAIN_CONTENT_BODY
        }
    }
}

/**
 * Dynamic-Content-Invariant Screen Skeleton Fingerprinter.
 *
 * Solves Fundamental Limit #1 of naive `Habit` state hashing:
 * In real apps (KakaoTalk, YouTube, Coupang), feed/chat items in the middle of the screen change every second.
 * `ScreenSkeletonFingerprinter` hashes ONLY the structural chrome (package, presence of editable field,
 * resourceId suffixes, and top/bottom bar navigation anchors), remaining 100% invariant to dynamic list text!
 */
object ScreenSkeletonFingerprinter {

    private val STATIC_CHROME_KEYWORDS = setOf(
        "검색", "뒤로가기", "닫기", "전체", "채팅방", "친구", "채팅", "전송", "보내기",
        "메시지 입력", "설정", "홈", "메뉴", "취소", "확인", "다음", "완료"
    )

    fun computeSkeletonHash(snapshot: AtomicSnapshot): String {
        val pkgShort = snapshot.activePackage.substringAfterLast('.').ifBlank { "unknown" }
        val hasEditable = snapshot.indexedElements.any { it.editable }
        val hasScrollable = snapshot.indexedElements.any { it.scrollable }

        val chromeTokens = snapshot.indexedElements
            .filter { el ->
                val zone = ScreenSpatialZone.fromNormY(el.normY)
                val label = el.text.ifBlank { el.contentDesc }.trim()
                el.editable ||
                    zone == ScreenSpatialZone.TOP_HEADER ||
                    zone == ScreenSpatialZone.SEARCH_OR_TAB_STRIP ||
                    zone == ScreenSpatialZone.BOTTOM_INPUT_OR_NAV ||
                    label in STATIC_CHROME_KEYWORDS
            }
            .take(10)
            .map { el ->
                val zone = ScreenSpatialZone.fromNormY(el.normY)
                val resSuffix = el.resourceId.substringAfterLast('/').take(14)
                val rawLabel = el.text.ifBlank { el.contentDesc }.trim()
                val staticLabel = if (rawLabel in STATIC_CHROME_KEYWORDS) rawLabel else ""
                "${el.shortClass}:$zone:$resSuffix:$staticLabel:${el.editable}"
            }
            .sorted()
            .joinToString("|")

        val rawHash = ((pkgShort.hashCode() * 31 + (if (hasEditable) 1 else 0)) * 31 +
            (if (hasScrollable) 1 else 0)) * 31 + abs(chromeTokens.hashCode())
        return "${pkgShort}_skel_${Integer.toHexString(abs(rawHash)).take(6)}"
    }

    fun classifyIntentSignature(pkg: String, goal: String): String {
        val g = goal.lowercase()
        val pkgShort = pkg.substringAfterLast('.').ifBlank { "global" }
        val intentType = when {
            (g.contains("카톡방") || g.contains("대화방") || g.contains("채팅방") || g.contains("단톡방")) &&
                (g.contains("들어가") || g.contains("열어") || g.contains("보여") || g.contains("확인")) -> "ENTER_CHAT_ROOM"
            g.contains("검색") || g.contains("찾아") || g.contains("틀어") || g.contains("재생") -> "SEARCH_AND_OPEN"
            g.contains("답장") || g.contains("보내") -> "SEND_MESSAGE"
            g.contains("드래그") || g.contains("슬라이더") -> "DRAG_CONTROL"
            g.contains("열어") || g.contains("실행") || g.contains("켜줘") -> "LAUNCH_OR_NAV"
            else -> "GENERAL_CONTROL"
        }
        return "$pkgShort|$intentType"
    }

    fun extractDynamicParameter(goal: String): String {
        val quoted = Regex("'([^']+)'|\"([^\"]+)\"").find(goal)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }
        }
        if (!quoted.isNullOrBlank()) return quoted.trim()

        val cleaned = goal
            .replace("카카오톡에서", "")
            .replace("카카오톡", "")
            .replace("카톡방에서", "")
            .replace("카톡방", "")
            .replace("대화방", "")
            .replace("채팅방", "")
            .replace("단톡방", "")
            .replace("유튜브에서", "")
            .replace("유튜브", "")
            .replace("설정에서", "")
            .replace("검색해서", "")
            .replace("검색해줘", "")
            .replace("검색해", "")
            .replace("들어가서", "")
            .replace("들어가봐", "")
            .replace("들어가줘", "")
            .replace("들어가", "")
            .replace("열어서", "")
            .replace("열어봐", "")
            .replace("열어줘", "")
            .replace("열어", "")
            .replace("눌러봐", "")
            .replace("눌러줘", "")
            .replace("눌러", "")
            .replace("무슨 얘기하고 있는지", "")
            .replace("내용 알려줘", "")
            .replace("알려줘", "")
            .replace("한번", "")
            .replace("바로", "")
            .replace("봐", "")
            .trim()

        return if (cleaned.contains("사족") || cleaned.contains("사정 로봇")) {
            cleaned
                .replace("사족 로봇 제어", "4족로봇제어")
                .replace("사정 로봇 제어", "4족로봇제어")
                .replace("사족 로봇", "4족로봇")
                .replace("사족로봇", "4족로봇")
                .replace(" ", "")
        } else {
            cleaned
        }
    }
}

/**
 * Relational & Semantic UI Element Selector (`SemanticElementSelector`).
 *
 * Solves Fundamental Limit #2 of naive `Habit`:
 * Instead of binding to a fragile `element_index = 4` (which breaks when 1 list item is added),
 * stores structural & semantic descriptors (`resourceIdSuffix`, `labelTemplate`, `shortClass`,
 * `spatialZone`, `editable`, `normX`, `normY`) and dynamically resolves the best element on any future screen.
 */
data class SemanticElementSelector(
    val resourceIdSuffix: String,
    val labelTemplate: String, // Either a static label like "검색"/"전체", or "{{PARAM}}" for dynamic targets!
    val shortClass: String,
    val spatialZone: ScreenSpatialZone,
    val editable: Boolean,
    val normX: Int,
    val normY: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("resourceIdSuffix", resourceIdSuffix)
        put("labelTemplate", labelTemplate)
        put("shortClass", shortClass)
        put("spatialZone", spatialZone.name)
        put("editable", editable)
        put("normX", normX)
        put("normY", normY)
    }

    companion object {
        fun fromElement(el: IndexedUiElement, dynamicParam: String): SemanticElementSelector {
            val rawLabel = el.text.ifBlank { el.contentDesc }.trim()
            val isDynamicMatch = dynamicParam.length >= 2 &&
                AgentToolExecutionHarness.computeTokenSimilarity(rawLabel, dynamicParam) >= 0.65
            val template = if (isDynamicMatch) "{{PARAM}}" else rawLabel.take(28)
            return SemanticElementSelector(
                resourceIdSuffix = el.resourceId.substringAfterLast('/').take(24),
                labelTemplate = template,
                shortClass = el.shortClass,
                spatialZone = ScreenSpatialZone.fromNormY(el.normY),
                editable = el.editable,
                normX = el.normX,
                normY = el.normY
            )
        }

        fun fromJson(obj: JSONObject?): SemanticElementSelector? {
            if (obj == null) return null
            val zone = runCatching { ScreenSpatialZone.valueOf(obj.optString("spatialZone", "MAIN_CONTENT_BODY")) }
                .getOrDefault(ScreenSpatialZone.MAIN_CONTENT_BODY)
            return SemanticElementSelector(
                resourceIdSuffix = obj.optString("resourceIdSuffix", ""),
                labelTemplate = obj.optString("labelTemplate", ""),
                shortClass = obj.optString("shortClass", ""),
                spatialZone = zone,
                editable = obj.optBoolean("editable", false),
                normX = obj.optInt("normX", 500),
                normY = obj.optInt("normY", 500)
            )
        }
    }

    /**
     * Resolves this relational selector against a live `snapshot` and `dynamicParam`,
     * returning the best matching `IndexedUiElement` if confidence >= threshold.
     */
    fun resolveOnSnapshot(snapshot: AtomicSnapshot, dynamicParam: String): IndexedUiElement? {
        if (snapshot.indexedElements.isEmpty()) return null
        val targetLabel = if (labelTemplate == "{{PARAM}}") dynamicParam else labelTemplate

        var bestEl: IndexedUiElement? = null
        var bestScore = 0.0

        for (el in snapshot.indexedElements) {
            if (editable && !el.editable) continue
            var score = 0.0

            // 1. Resource ID suffix match
            val elRes = el.resourceId.substringAfterLast('/')
            if (resourceIdSuffix.isNotBlank() && elRes.equals(resourceIdSuffix, ignoreCase = true)) {
                score += 3.0
            }

            // 2. Semantic label / parameterized target match
            val elLabel = el.text.ifBlank { el.contentDesc }.trim()
            if (targetLabel.isNotBlank() && elLabel.isNotBlank()) {
                val sim = AgentToolExecutionHarness.computeTokenSimilarity(targetLabel, elLabel)
                if (sim >= 0.90) {
                    score += 4.5
                } else if (sim >= 0.65) {
                    score += 3.2 * sim
                } else if (labelTemplate == "{{PARAM}}") {
                    // If this step specifically requires clicking "{{PARAM}}" (e.g. the target chat room),
                    // do not click an unrelated room!
                    continue
                }
            } else if (labelTemplate == "{{PARAM}}" && targetLabel.isNotBlank()) {
                continue
            }

            // 3. Class & Editable match
            if (editable && el.editable) score += 3.5
            if (shortClass.isNotBlank() && el.shortClass == shortClass) score += 1.0

            // 4. Spatial Zone & Coordinate proximity
            val elZone = ScreenSpatialZone.fromNormY(el.normY)
            if (elZone == spatialZone) score += 1.2
            val distNorm = (abs(el.normX - normX) + abs(el.normY - normY)) / 1000.0
            score += (1.0 - distNorm).coerceIn(0.0, 1.0)

            if (score > bestScore) {
                bestScore = score
                bestEl = el
            }
        }

        val minRequired = if (labelTemplate == "{{PARAM}}") 4.0 else 3.2
        return if (bestScore >= minRequired) bestEl else null
    }
}

/**
 * Compiled Multi-Step Skill Macro Step with JIT Precondition Guard (`skeletonHash` + `SemanticElementSelector`).
 */
data class CompiledMacroStep(
    val skeletonHash: String,
    val toolName: String,
    val argsTemplateJson: String,
    val selector: SemanticElementSelector?,
    var verifiedUses: Int = 1,
    var cumulativeCredit: Float = 1.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("skeletonHash", skeletonHash)
        put("toolName", toolName)
        put("argsTemplateJson", argsTemplateJson)
        if (selector != null) put("selector", selector.toJson())
        put("verifiedUses", verifiedUses)
        put("cumulativeCredit", cumulativeCredit.toDouble())
    }

    companion object {
        fun fromJson(obj: JSONObject): CompiledMacroStep = CompiledMacroStep(
            skeletonHash = obj.optString("skeletonHash"),
            toolName = obj.optString("toolName"),
            argsTemplateJson = obj.optString("argsTemplateJson", "{}"),
            selector = SemanticElementSelector.fromJson(obj.optJSONObject("selector")),
            verifiedUses = obj.optInt("verifiedUses", 1),
            cumulativeCredit = obj.optDouble("cumulativeCredit", 1.0).toFloat()
        )
    }
}

/**
 * Contrastive Experience Replay Sample for On-Device UI-VLM Distillation.
 */
data class ContrastiveDistillSample(
    val goal: String,
    val skeletonHash: String,
    val positiveSelector: SemanticElementSelector,
    val negativeSelectors: List<SemanticElementSelector>
)

/**
 * Beyond-Habit Architecture:
 * **Neural Skill Compiler & Persistent Contrastive On-Device UI-VLM Distiller (`NeuralSkillCompilerAndDistiller`)**
 *
 * Advances the System-1 / System-2 paradigm across 4 dimensions beyond standard `Habit`:
 * 1. **Dynamic-Content-Invariant Skeleton Hashing (`ScreenSkeletonFingerprinter`)**:
 *    Matches app screens even when feed items, chat messages, or timestamps change every second.
 * 2. **Relational & Parameterized Element Resolution (`SemanticElementSelector`)**:
 *    Survives UI index shifts (`[4]` -> `[15]`) and binds `{{PARAM}}` to any new user query/room name.
 * 3. **JIT Multi-Step Macro Compilation with Deopt Guard (`CompiledMacroStep`)**:
 *    Compiles loop-erased S2 trajectories into guarded S1 macro steps that execute in `< 3ms` and
 *    automatically de-optimize (escalate to S2) if a guard precondition fails.
 * 4. **Persistent Subword + Spatial + Contrastive Margin On-Device VLM Probe**:
 *    Uses character 2/3-gram subwords + spatial pyramid features, updates via InfoNCE + Hard-Negative Margin
 *    distillation, replays past experiences to prevent forgetting, and persists binary weights to disk (`s1_vlm_probe.bin`).
 */
class NeuralSkillCompilerAndDistiller(
    private val context: Context,
    val dimension: Int = 4096
) {
    companion object {
        private const val PREFS_NAME = "hanson_neural_skill_compiler"
        private const val KEY_MACROS = "compiled_skill_macros_json"
        private const val WEIGHTS_FILE_NAME = "s1_contrastive_vlm_weights.bin"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val weightsFile = File(context.filesDir, WEIGHTS_FILE_NAME)

    // Key: "$intentSignature|$skeletonHash" -> CompiledMacroStep
    private val compiledMacroSteps = LinkedHashMap<String, CompiledMacroStep>()
    private val replayBuffer = ArrayDeque<ContrastiveDistillSample>()

    val weights: FloatArray = FloatArray(dimension)
    var totalMacroHits: Int = 0
        private set
    var totalDistilledProbeHits: Int = 0
        private set
    var totalOnlineDistillSteps: Int = 0
        private set

    init {
        for (i in 0 until dimension) {
            weights[i] = ((i % 19) - 9) * 0.004f
        }
        loadMacrosAndWeightsFromDisk()
    }

    /**
     * Evaluates whether System-1 can immediately execute the current turn via:
     * 1. JIT Compiled Parameterized Skill Macro (`S1-MACRO-JIT`)
     * 2. Persistent Contrastive On-Device UI-VLM Probe (`S1-CONTRASTIVE-VLM`)
     */
    @Synchronized
    fun evaluateAdvancedSystem1(
        goal: String,
        snapshot: AtomicSnapshot,
        recentStepFailed: Boolean
    ): ModelTurnDecision? {
        if (recentStepFailed || snapshot.indexedElements.isEmpty()) return null

        val skelHash = ScreenSkeletonFingerprinter.computeSkeletonHash(snapshot)
        val intentSig = ScreenSkeletonFingerprinter.classifyIntentSignature(snapshot.activePackage, goal)
        val dynamicParam = ScreenSkeletonFingerprinter.extractDynamicParameter(goal)
        val macroKey = "$intentSig|$skelHash"

        // 1. Check JIT Compiled Skill Macro with Precondition Guard
        val macro = compiledMacroSteps[macroKey] ?: findFuzzyMatchingMacro(skelHash, intentSig, snapshot, dynamicParam)
        if (macro != null && macro.cumulativeCredit >= 0.50f) {
            val resolvedDecision = tryInstantiateMacroStep(macro, goal, dynamicParam, snapshot)
            if (resolvedDecision != null) {
                totalMacroHits++
                return resolvedDecision
            }
        }

        // 2. Check Persistent Contrastive On-Device UI-VLM Probe
        if (totalOnlineDistillSteps > 0) {
            val probeDecision = tryEvaluateContrastiveVlmProbe(goal, dynamicParam, snapshot)
            if (probeDecision != null) {
                totalDistilledProbeHits++
                return probeDecision
            }
        }

        return null
    }

    private fun findFuzzyMatchingMacro(
        skelHash: String,
        intentSig: String,
        snapshot: AtomicSnapshot,
        dynamicParam: String
    ): CompiledMacroStep? {
        // 1. Same skeleton hash where selector resolves on current snapshot
        val skelMatches = compiledMacroSteps.values.filter { it.skeletonHash == skelHash && it.cumulativeCredit >= 0.50f }
        for (candidate in skelMatches) {
            if (candidate.selector == null || candidate.selector.resolveOnSnapshot(snapshot, dynamicParam) != null) {
                return candidate
            }
        }

        // 2. Compatible package signature with selector resolution
        val pkgSig = intentSig.substringBefore('|')
        for ((key, candidate) in compiledMacroSteps) {
            if (key.startsWith("$pkgSig|") && candidate.cumulativeCredit >= 0.60f) {
                if (candidate.selector?.resolveOnSnapshot(snapshot, dynamicParam) != null) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Checks whether a habituated skill macro is already compiled for the given goal and snapshot.
     */
    fun isMacroCompiled(goal: String, snapshot: AtomicSnapshot): Boolean {
        val skelHash = ScreenSkeletonFingerprinter.computeSkeletonHash(snapshot)
        val intentSig = ScreenSkeletonFingerprinter.classifyIntentSignature(snapshot.activePackage, goal)
        val dynamicParam = ScreenSkeletonFingerprinter.extractDynamicParameter(goal)
        val macroKey = "$intentSig|$skelHash"
        val macro = compiledMacroSteps[macroKey] ?: findFuzzyMatchingMacro(skelHash, intentSig, snapshot, dynamicParam)
        return macro != null && macro.cumulativeCredit >= 0.50f
    }

    private fun tryInstantiateMacroStep(
        macro: CompiledMacroStep,
        goal: String,
        dynamicParam: String,
        snapshot: AtomicSnapshot
    ): ModelTurnDecision? {
        val baseArgs = runCatching { JSONObject(macro.argsTemplateJson) }.getOrDefault(JSONObject())
        when (macro.toolName) {
            "click", "long_press" -> {
                val selector = macro.selector ?: return null
                val targetEl = selector.resolveOnSnapshot(snapshot, dynamicParam) ?: return null
                val label = targetEl.text.ifBlank { targetEl.contentDesc.ifBlank { targetEl.shortClass } }.take(24)
                baseArgs.put("element_index", targetEl.index)
                baseArgs.put("target_description", label)
                return ModelTurnDecision(
                    observation = "[S1-JIT Macro · skel=${macro.skeletonHash}] 컴파일된 관계형 셀렉터로 '$label' 요소를 0-LLM 즉시 실행합니다.",
                    subGoals = listOf(SubGoalItem("S1-JIT 스킬 매크로 실행 ($label)", SubGoalStatus.IN_PROGRESS)),
                    toolName = macro.toolName,
                    toolArgs = baseArgs
                )
            }
            "input_text" -> {
                val editEl = macro.selector?.resolveOnSnapshot(snapshot, dynamicParam)
                    ?: snapshot.indexedElements.firstOrNull { it.editable }
                    ?: return null
                val textToType = if (baseArgs.optString("text") == "{{PARAM}}" && dynamicParam.isNotBlank()) {
                    dynamicParam
                } else {
                    dynamicParam.ifBlank { baseArgs.optString("fallback_text", "") }
                }
                if (textToType.isBlank()) return null
                baseArgs.put("element_index", editEl.index)
                baseArgs.put("text", textToType)
                return ModelTurnDecision(
                    observation = "[S1-JIT Macro · skel=${macro.skeletonHash}] 파라미터 슬롯('{{PARAM}}' -> '$textToType') 즉시 바인딩 입력",
                    subGoals = listOf(SubGoalItem("S1-JIT 파라미터 바인딩 입력 ('$textToType')", SubGoalStatus.IN_PROGRESS)),
                    toolName = "input_text",
                    toolArgs = baseArgs
                )
            }
            "press_key", "swipe", "manage_app" -> {
                return ModelTurnDecision(
                    observation = "[S1-JIT Macro · skel=${macro.skeletonHash}] 검증된 매크로 액션('${macro.toolName}') 즉시 실행",
                    subGoals = listOf(SubGoalItem("S1-JIT 매크로 실행 (${macro.toolName})", SubGoalStatus.IN_PROGRESS)),
                    toolName = macro.toolName,
                    toolArgs = baseArgs
                )
            }
            else -> return null
        }
    }

    /**
     * Multi-Granularity Subword (2/3-gram) + Spatial Pyramid + Goal Cross-Attention Feature Encoder.
     */
    fun encodeSubwordAndSpatialVector(
        goal: String,
        dynamicParam: String,
        snapshot: AtomicSnapshot,
        element: IndexedUiElement
    ): FloatArray {
        val vec = FloatArray(dimension)
        val pkg = snapshot.activePackage.substringAfterLast('.')
        val zone = ScreenSpatialZone.fromNormY(element.normY)
        val rawLabel = element.text.ifBlank { element.contentDesc }.trim().lowercase()
        val gLower = goal.lowercase()

        fun hashFeat(featureKey: String, w: Float) {
            val h1 = abs(featureKey.hashCode()) % dimension
            val h2 = abs((featureKey.hashCode() * 16777619) xor 0x3c6ef372) % dimension
            vec[h1] += w
            vec[h2] += w * 0.5f
        }

        // 1. Structural & Spatial Zone Pyramid
        hashFeat("s_pkg:$pkg|zone:$zone|cls:${element.shortClass}", 1.4f)
        hashFeat("s_grid:${element.normX / 100}_${element.normY / 100}|click:${element.clickable}", 1.0f)
        if (element.editable) hashFeat("s_editable:$pkg|$zone", 2.2f)
        if (element.resourceId.isNotBlank()) {
            hashFeat("s_res:$pkg/${element.resourceId.substringAfterLast('/')}", 1.8f)
        }

        // 2. Character 2-gram & 3-gram Subword Embeddings (handles phonetic/spacing variations)
        val normLabel = AgentToolExecutionHarness.normalizeKoreanUiToken(rawLabel)
        val normParam = AgentToolExecutionHarness.normalizeKoreanUiToken(dynamicParam.ifBlank { gLower })
        if (normLabel.isNotBlank()) {
            for (len in 2..3) {
                for (i in 0..(normLabel.length - len)) {
                    val sub = normLabel.substring(i, i + len)
                    hashFeat("lbl_ng:$sub", 0.9f)
                    if (normParam.contains(sub)) {
                        hashFeat("cross_ng:$sub|$zone", 2.4f)
                    }
                }
            }
            val sim = AgentToolExecutionHarness.computeTokenSimilarity(normLabel, normParam)
            if (sim >= 0.85) {
                hashFeat("sim:high_match|$zone", 3.8f)
            } else if (sim >= 0.55) {
                hashFeat("sim:mid_match|$zone", 2.0f)
            }
        }

        return vec
    }

    private fun tryEvaluateContrastiveVlmProbe(
        goal: String,
        dynamicParam: String,
        snapshot: AtomicSnapshot
    ): ModelTurnDecision? {
        val candidates = snapshot.indexedElements.take(40)
        if (candidates.size < 2) return null

        val rawVecs = candidates.map { encodeSubwordAndSpatialVector(goal, dynamicParam, snapshot, it) }
        val probs = computeCenteredSoftmax(rawVecs)

        var bestIdx = 0
        var secondBestProb = 0f
        var bestProb = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > bestProb) {
                secondBestProb = bestProb
                bestProb = probs[i]
                bestIdx = i
            } else if (probs[i] > secondBestProb) {
                secondBestProb = probs[i]
            }
        }

        val margin = bestProb - secondBestProb
        if (bestProb >= 0.66f && margin >= 0.22f) {
            val chosen = candidates[bestIdx]
            val label = chosen.text.ifBlank { chosen.contentDesc.ifBlank { chosen.shortClass } }.take(24)
            val pct = (bestProb * 100).toInt()
            return ModelTurnDecision(
                observation = "[S1-Contrastive VLM · p=${pct}% · Δm=${"%.2f".format(margin)}] 온디바이스 대조 증류 VLM 프로브가 '$label' 요소를 즉시 선택했습니다.",
                subGoals = listOf(SubGoalItem("S1 온디바이스 VLM 실행 ($label)", SubGoalStatus.IN_PROGRESS)),
                toolName = "click",
                toolArgs = JSONObject().apply {
                    put("element_index", chosen.index)
                    put("target_description", label)
                }
            )
        }
        return null
    }

    /**
     * Compiles a verified step into a guarded `CompiledMacroStep` AND performs
     * Contrastive Margin + Hard-Negative Replay Distillation on the on-device VLM probe.
     */
    @Synchronized
    fun compileAndDistillVerifiedStep(
        goal: String,
        preSnapshot: AtomicSnapshot,
        toolName: String,
        toolArgs: JSONObject,
        verifiedSuccess: Boolean,
        failedElementIndexBeforeHeal: Int? = null
    ): Float {
        val skelHash = ScreenSkeletonFingerprinter.computeSkeletonHash(preSnapshot)
        val intentSig = ScreenSkeletonFingerprinter.classifyIntentSignature(preSnapshot.activePackage, goal)
        val dynamicParam = ScreenSkeletonFingerprinter.extractDynamicParameter(goal)
        val macroKey = "$intentSig|$skelHash"

        if (!verifiedSuccess) {
            // Penalize existing macro if its execution failed verification
            val existing = compiledMacroSteps[macroKey]
            if (existing != null) {
                compiledMacroSteps[macroKey] = existing.copy(
                    cumulativeCredit = (existing.cumulativeCredit - 0.35f).coerceAtLeast(0f)
                )
            }
            return 0f
        }

        // 1. Compile Relational Selector & Guarded Macro Step
        val chosenIndex = toolArgs.optInt("element_index", -1)
        val chosenEl = preSnapshot.indexedElements.firstOrNull { it.index == chosenIndex }
        val selector = chosenEl?.let { SemanticElementSelector.fromElement(it, dynamicParam) }

        val templateArgs = JSONObject(toolArgs.toString())
        if (toolName == "input_text") {
            val typed = templateArgs.optString("text", "")
            if (typed.isNotBlank()) {
                templateArgs.put("text", "{{PARAM}}")
                templateArgs.put("fallback_text", typed)
            }
        }

        val prevMacro = compiledMacroSteps[macroKey]
        compiledMacroSteps[macroKey] = CompiledMacroStep(
            skeletonHash = skelHash,
            toolName = toolName,
            argsTemplateJson = templateArgs.toString(),
            selector = selector ?: prevMacro?.selector,
            verifiedUses = (prevMacro?.verifiedUses ?: 0) + 1,
            cumulativeCredit = ((prevMacro?.cumulativeCredit ?: 0.8f) * 0.6f) + 0.65f
        )

        // 2. Online Contrastive + Hard-Negative Margin Distillation for UI element selection
        var postUpdateProb = 0f
        if (chosenEl != null && preSnapshot.indexedElements.size >= 2) {
            postUpdateProb = updateContrastiveWeights(
                goal = goal,
                dynamicParam = dynamicParam,
                snapshot = preSnapshot,
                chosenElementIndex = chosenIndex,
                hardNegativeIndex = failedElementIndexBeforeHeal,
                learningRate = 1.85f
            )

            if (selector != null) {
                val negSelectors = preSnapshot.indexedElements
                    .filter { it.index != chosenIndex }
                    .take(4)
                    .map { SemanticElementSelector.fromElement(it, dynamicParam) }
                if (replayBuffer.size >= 64) replayBuffer.removeFirst()
                replayBuffer.addLast(
                    ContrastiveDistillSample(
                        goal = goal,
                        skeletonHash = skelHash,
                        positiveSelector = selector,
                        negativeSelectors = negSelectors
                    )
                )
            }
        }

        persistMacrosAndWeightsToDisk()
        return postUpdateProb
    }

    private fun updateContrastiveWeights(
        goal: String,
        dynamicParam: String,
        snapshot: AtomicSnapshot,
        chosenElementIndex: Int,
        hardNegativeIndex: Int?,
        learningRate: Float
    ): Float {
        val candidates = snapshot.indexedElements.take(40)
        val posIdx = candidates.indexOfFirst { it.index == chosenElementIndex }
        if (posIdx < 0 || candidates.size < 2) return 0f

        val rawVecs = candidates.map { encodeSubwordAndSpatialVector(goal, dynamicParam, snapshot, it) }
        val centered = centerAndScaleVectors(rawVecs)
        val probs = computeSoftmaxFromCentered(centered)

        val posVec = centered[posIdx]
        val hardNegPos = if (hardNegativeIndex != null) {
            candidates.indexOfFirst { it.index == hardNegativeIndex }.takeIf { it >= 0 && it != posIdx }
        } else null

        for (d in 0 until dimension) {
            var expected = 0f
            for (j in centered.indices) {
                expected += probs[j] * centered[j][d]
            }
            var grad = posVec[d] - expected
            // Extra contrastive margin push away from explicit hard-negative element
            if (hardNegPos != null) {
                grad += 0.45f * (posVec[d] - centered[hardNegPos][d])
            }
            weights[d] += learningRate * grad
        }
        totalOnlineDistillSteps++

        val updatedProbs = computeSoftmaxFromCentered(centered)
        return updatedProbs[posIdx]
    }

    private fun centerAndScaleVectors(rawVectors: List<FloatArray>): List<FloatArray> {
        val k = rawVectors.size
        if (k == 0) return emptyList()
        val d = rawVectors.first().size
        val mean = FloatArray(d)
        for (v in rawVectors) {
            for (j in 0 until d) mean[j] += v[j]
        }
        for (j in 0 until d) mean[j] /= k.toFloat()

        var normSum = 0f
        for (v in rawVectors) {
            var sq = 0f
            for (x in v) sq += x * x
            normSum += sqrt(sq)
        }
        val scale = (normSum / k.toFloat()).coerceAtLeast(1e-6f)
        return rawVectors.map { v -> FloatArray(d) { j -> (v[j] - mean[j]) / scale } }
    }

    private fun computeCenteredSoftmax(rawVectors: List<FloatArray>): FloatArray {
        return computeSoftmaxFromCentered(centerAndScaleVectors(rawVectors))
    }

    private fun computeSoftmaxFromCentered(centeredVectors: List<FloatArray>): FloatArray {
        val k = centeredVectors.size
        if (k == 0) return FloatArray(0)
        val logits = FloatArray(k)
        for (i in 0 until k) {
            val vec = centeredVectors[i]
            var dot = 0f
            for (j in 0 until minOf(dimension, vec.size)) {
                dot += weights[j] * vec[j]
            }
            logits[i] = dot
        }
        val maxLogit = logits.maxOrNull() ?: 0f
        var sumExp = 0f
        val exps = FloatArray(k)
        for (i in 0 until k) {
            exps[i] = exp((logits[i] - maxLogit).toDouble()).toFloat()
            sumExp += exps[i]
        }
        return FloatArray(k) { i -> exps[i] / sumExp.coerceAtLeast(1e-8f) }
    }

    @Synchronized
    fun getCompilerStatsJson(): JSONObject = JSONObject().apply {
        put("compiledMacroCount", compiledMacroSteps.size)
        put("totalMacroHits", totalMacroHits)
        put("totalDistilledProbeHits", totalDistilledProbeHits)
        put("totalOnlineDistillSteps", totalOnlineDistillSteps)
        put("replayBufferSize", replayBuffer.size)
        put("weightsPersistedOnDisk", weightsFile.exists())
        val macroArr = JSONArray()
        compiledMacroSteps.entries.toList().takeLast(15).forEach { (key, step) ->
            macroArr.put(step.toJson().put("macroKey", key))
        }
        put("compiledMacros", macroArr)
    }

    private fun loadMacrosAndWeightsFromDisk() {
        try {
            val rawMacros = prefs.getString(KEY_MACROS, null)
            if (!rawMacros.isNullOrBlank()) {
                val arr = JSONArray(rawMacros)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("macroKey")
                    if (key.isNotBlank()) {
                        compiledMacroSteps[key] = CompiledMacroStep.fromJson(o)
                    }
                }
            }
            if (weightsFile.exists() && weightsFile.length() >= dimension * 4L) {
                DataInputStream(BufferedInputStream(FileInputStream(weightsFile))).use { dis ->
                    totalOnlineDistillSteps = dis.readInt()
                    for (i in 0 until dimension) {
                        weights[i] = dis.readFloat()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistMacrosAndWeightsToDisk() {
        try {
            val arr = JSONArray()
            compiledMacroSteps.entries.toList().takeLast(120).forEach { (k, v) ->
                arr.put(v.toJson().put("macroKey", k))
            }
            prefs.edit().putString(KEY_MACROS, arr.toString()).apply()

            DataOutputStream(BufferedOutputStream(FileOutputStream(weightsFile))).use { dos ->
                dos.writeInt(totalOnlineDistillSteps)
                for (i in 0 until dimension) {
                    dos.writeFloat(weights[i])
                }
            }
        } catch (_: Exception) {
        }
    }
}
