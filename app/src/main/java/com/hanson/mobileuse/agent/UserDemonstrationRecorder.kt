package com.hanson.mobileuse.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class RecordedUserStep(
    val stepIndex: Int,
    val packageName: String,
    val eventType: String, // "CLICK", "TEXT", "WINDOW"
    val targetLabel: String,
    val resourceId: String,
    val normX: Int,
    val normY: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toRecipeStepString(): String = when (eventType) {
        "TEXT" -> "[입력] '$targetLabel' 입력 (id=${resourceId.ifBlank { "auto" }})"
        "WINDOW" -> "[화면전환] $packageName ($targetLabel)"
        else -> "[터치] '${targetLabel.ifBlank { resourceId.ifBlank { "요소" } }}' 탭 @($normX, $normY)"
    }
}

data class DemoParameterSlot(
    val name: String,
    val labelKo: String,
    val defaultValue: String,
    val stepIndex: Int,
    val fieldHint: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("labelKo", labelKo)
        put("defaultValue", defaultValue)
        put("stepIndex", stepIndex)
        put("fieldHint", fieldHint)
    }
}

data class ParameterizedDemoStep(
    val stepIndex: Int,
    val packageName: String,
    val eventType: String,
    val semanticTarget: String,
    val resourceId: String,
    val fallbackNormX: Int,
    val fallbackNormY: Int,
    val templateText: String,
    val parameterSlotName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stepIndex", stepIndex)
        put("packageName", packageName)
        put("eventType", eventType)
        put("semanticTarget", semanticTarget)
        put("resourceId", resourceId)
        put("fallbackNormX", fallbackNormX)
        put("fallbackNormY", fallbackNormY)
        put("templateText", templateText)
        put("parameterSlotName", parameterSlotName ?: JSONObject.NULL)
    }
}

data class ParameterizedDemoTemplate(
    val id: String,
    val titleKo: String,
    val packageName: String,
    val parameters: List<DemoParameterSlot>,
    val steps: List<ParameterizedDemoStep>,
    val recipeTemplateText: String,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("titleKo", titleKo)
        put("packageName", packageName)
        val paramsArr = JSONArray()
        parameters.forEach { paramsArr.put(it.toJson()) }
        put("parameters", paramsArr)
        val stepsArr = JSONArray()
        steps.forEach { stepsArr.put(it.toJson()) }
        put("steps", stepsArr)
        put("recipeTemplateText", recipeTemplateText)
        put("createdAtMs", createdAtMs)
    }
}

/**
 * "Show-Me-Once" (시연으로 가르치기 / Teach-a-Task v2) Recorder:
 * Watches the user's real touches & text inputs across any app via `HansonAccessibilityService`,
 * compiles reusable UARP App Skills, detects variable input slots (`{{recipient}}`, `{{date}}`,
 * `{{item}}`, `{{query}}`), and generates live Google A2UI v1.0 parameter forms for replay.
 */
object UserDemonstrationRecorder {

    const val ACTION_RUN_TEMPLATE = "demo.run_template"

    private val DATE_LIKE_REGEX = Regex(
        "(?i)(?:오늘|내일|모레|글피|(?:월|화|수|목|금|토|일)요일|\\d{1,2}월\\s*\\d{1,2}일|\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}:\\d{2}|(?:오전|오후)\\s*\\d{1,2}시|today|tomorrow)"
    )
    private val RECIPIENT_HINT_REGEX = Regex(
        "(?i)(?:recipient|contact|friend|chat|to_field|receiver|수신|받는|친구|대화방|이름)"
    )
    private val ITEM_HINT_REGEX = Regex(
        "(?i)(?:product|menu|item|store|restaurant|station|상품|메뉴|매장|식당|음식|물품|출발|도착)"
    )
    private val DATE_HINT_REGEX = Regex("(?i)(?:date|time|schedule|calendar|날짜|시간|일시)")

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedSteps = MutableStateFlow<List<RecordedUserStep>>(emptyList())
    val recordedSteps: StateFlow<List<RecordedUserStep>> = _recordedSteps.asStateFlow()

    private val stepsBuffer = CopyOnWriteArrayList<RecordedUserStep>()
    private var primaryPackage: String = ""

    fun startRecording() {
        stepsBuffer.clear()
        primaryPackage = ""
        _recordedSteps.value = emptyList()
        _isRecording.value = true
    }

    fun recordStep(
        packageName: String,
        eventType: String,
        targetLabel: String,
        resourceId: String,
        normX: Int,
        normY: Int
    ) {
        if (!_isRecording.value) return
        if (packageName == "com.hanson.mobileuse") return // Ignore touches inside Hanson controller UI

        if (primaryPackage.isBlank() && packageName.isNotBlank()) {
            primaryPackage = packageName
        }

        // Deduplicate rapid identical window events
        val last = stepsBuffer.lastOrNull()
        if (last != null &&
            last.eventType == eventType &&
            last.targetLabel == targetLabel &&
            (System.currentTimeMillis() - last.timestampMs) < 400
        ) {
            return
        }

        val step = RecordedUserStep(
            stepIndex = stepsBuffer.size + 1,
            packageName = packageName,
            eventType = eventType,
            targetLabel = targetLabel.take(60),
            resourceId = resourceId.substringAfterLast("/"),
            normX = normX,
            normY = normY
        )
        stepsBuffer.add(step)
        _recordedSteps.value = stepsBuffer.toList()
    }

    /**
     * Stops recording and compiles the user's demonstration into a structured UARP recipe string
     * and target package name.
     */
    fun stopAndCompile(): Pair<String, String> {
        _isRecording.value = false
        val steps = stepsBuffer.toList()
        if (steps.isEmpty()) {
            return "현재앱" to ""
        }
        val pkg = primaryPackage.ifBlank { steps.first().packageName }
        val compiledRecipe = buildString {
            append("[사용자 직접 시연 루틴 (${steps.size}단계)] ")
            append(steps.joinToString(" → ") { "${it.stepIndex}.${it.toRecipeStepString()}" })
        }
        return pkg to compiledRecipe
    }

    /**
     * Teach-a-Task v2: extracts variable inputs (`{{recipient}}`, `{{date}}`, `{{item}}`, `{{query}}`)
     * from recorded steps while preferring semantic accessibility targets (`targetLabel`/`resourceId`)
     * with coordinate fallback for self-healing replay. Sensitive secrets are never embedded as defaults.
     */
    fun extractParameterizedTemplate(
        steps: List<RecordedUserStep>,
        title: String = ""
    ): ParameterizedDemoTemplate {
        val targetPkg = steps.firstOrNull { it.packageName.isNotBlank() }?.packageName ?: "현재앱"
        val appLabel = AppDetailPlaybookRegistry.resolveAppLabelKo(targetPkg)
            ?: targetPkg.substringAfterLast('.').ifBlank { "현재 앱" }
        val resolvedTitle = title.trim().ifBlank { "$appLabel 시연 자동화 루틴" }

        val usedSlotCounts = LinkedHashMap<String, Int>()
        val parameters = mutableListOf<DemoParameterSlot>()
        val paramSteps = mutableListOf<ParameterizedDemoStep>()

        steps.forEachIndexed { idx, step ->
            val prevStep = steps.getOrNull(idx - 1)
            if (step.eventType.equals("TEXT", ignoreCase = true) && step.targetLabel.isNotBlank()) {
                val (baseSlot, labelKo, hintKo) = classifySlot(step, prevStep)
                val count = (usedSlotCounts[baseSlot] ?: 0) + 1
                usedSlotCounts[baseSlot] = count
                val slotName = if (count == 1) baseSlot else "${baseSlot}_$count"
                val finalLabel = if (count == 1) labelKo else "$labelKo $count"

                // Privacy hardline: never embed sensitive secrets as default template values
                val safeDefault = if (ConvenienceSecretGuard.containsSensitiveSecret(step.targetLabel)) {
                    ""
                } else {
                    step.targetLabel.trim()
                }
                parameters.add(
                    DemoParameterSlot(
                        name = slotName,
                        labelKo = finalLabel,
                        defaultValue = safeDefault,
                        stepIndex = step.stepIndex,
                        fieldHint = hintKo
                    )
                )
                paramSteps.add(
                    ParameterizedDemoStep(
                        stepIndex = step.stepIndex,
                        packageName = step.packageName,
                        eventType = "TEXT",
                        semanticTarget = step.resourceId.ifBlank { prevStep?.targetLabel.orEmpty() },
                        resourceId = step.resourceId,
                        fallbackNormX = step.normX,
                        fallbackNormY = step.normY,
                        templateText = "[입력] '{{$slotName}}' 입력 (semantic=${step.resourceId.ifBlank { "focused_input" }})",
                        parameterSlotName = slotName
                    )
                )
            } else {
                val semantic = step.targetLabel.ifBlank { step.resourceId.ifBlank { "요소" } }
                val stepText = when (step.eventType.uppercase(Locale.ROOT)) {
                    "WINDOW" -> "[화면전환] ${step.packageName} ($semantic)"
                    else -> "[터치] semantic='$semantic' (id=${step.resourceId.ifBlank { "none" }}, fallback=@(${step.normX},${step.normY}))"
                }
                paramSteps.add(
                    ParameterizedDemoStep(
                        stepIndex = step.stepIndex,
                        packageName = step.packageName,
                        eventType = step.eventType.uppercase(Locale.ROOT),
                        semanticTarget = semantic,
                        resourceId = step.resourceId,
                        fallbackNormX = step.normX,
                        fallbackNormY = step.normY,
                        templateText = stepText,
                        parameterSlotName = null
                    )
                )
            }
        }

        val recipeTemplate = buildString {
            append("[시연 템플릿: $resolvedTitle (${paramSteps.size}단계 · semantic-first)] ")
            append(paramSteps.joinToString(" → ") { "${it.stepIndex}.${it.templateText}" })
        }
        val idSeed = "${targetPkg}_${resolvedTitle}_${paramSteps.size}".hashCode() and 0x7FFFFFFF
        return ParameterizedDemoTemplate(
            id = "demo_${idSeed.toString(16)}",
            titleKo = resolvedTitle,
            packageName = targetPkg,
            parameters = parameters,
            steps = paramSteps,
            recipeTemplateText = recipeTemplate
        )
    }

    /**
     * Renders a [ParameterizedDemoTemplate] with runtime parameter [values], falling back to
     * safe default values when a slot is omitted.
     */
    fun renderWithParams(
        template: ParameterizedDemoTemplate,
        values: Map<String, String>
    ): String {
        var rendered = template.recipeTemplateText
        for (slot in template.parameters) {
            val rawValue = values[slot.name]?.trim()?.takeIf { it.isNotEmpty() } ?: slot.defaultValue
            val sanitized = rawValue.replace(Regex("[\\r\\n]+"), " ").take(120)
            rendered = rendered.replace("{{${slot.name}}}", sanitized)
        }
        return rendered
    }

    /**
     * Emits a live Google A2UI v1.0 parameter input form (`TextField` per slot + `demo.run_template`
     * primary submit `Button`) ready for in-app or overlay rendering.
     */
    fun toParameterizedA2uiJsonl(
        template: ParameterizedDemoTemplate,
        surfaceId: String = "demo_form_${template.id}",
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val ver = if (protocolVersion == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.VERSION_0_9
        } else {
            A2uiProtocolConstants.VERSION_1_0
        }
        val cat = if (ver == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.BASIC_CATALOG_V0_9
        } else {
            A2uiProtocolConstants.BASIC_CATALOG_V1_0
        }

        val rootChildren = JSONArray().put("demo_title").put("demo_sub")
        val components = JSONArray()
        val paramsData = JSONObject()
        val actionContext = JSONObject()
            .put("templateId", JSONObject().put("@path", "/demo/templateId"))
            .put("packageName", JSONObject().put("@path", "/demo/packageName"))
            .put("params", JSONObject().put("@path", "/demo/params"))

        components.put(
            JSONObject()
                .put("id", "demo_title")
                .put("component", "Text")
                .put("text", JSONObject().put("@path", "/demo/titleKo"))
                .put("variant", "h3")
        )
        components.put(
            JSONObject()
                .put("id", "demo_sub")
                .put("component", "Text")
                .put("text", JSONObject().put("@path", "/demo/subtitleKo"))
                .put("variant", "caption")
        )

        template.parameters.forEachIndexed { idx, slot ->
            val fieldId = "demo_field_$idx"
            val path = "/demo/params/${slot.name}"
            paramsData.put(slot.name, slot.defaultValue)
            actionContext.put(slot.name, JSONObject().put("@path", path))
            rootChildren.put(fieldId)
            components.put(
                JSONObject()
                    .put("id", fieldId)
                    .put("component", "TextField")
                    .put("label", slot.labelKo)
                    .put("value", JSONObject().put("@path", path))
            )
        }

        rootChildren.put("demo_run_btn")
        components.put(
            JSONObject()
                .put("id", "demo_run_lbl")
                .put("component", "Text")
                .put("text", "루틴 실행하기")
                .put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "demo_run_btn")
                .put("component", "Button")
                .put("variant", "primary")
                .put("child", "demo_run_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_RUN_TEMPLATE)
                            .put("context", actionContext)
                    )
                )
        )

        val allComponents = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            for (i in 0 until components.length()) put(components.get(i))
        }

        val demoData = JSONObject()
            .put("templateId", template.id)
            .put("titleKo", template.titleKo)
            .put("subtitleKo", "대상 앱: ${template.packageName} · 변수 ${template.parameters.size}개")
            .put("packageName", template.packageName)
            .put("params", paramsData)

        return listOf(
            JSONObject().put("version", ver).put(
                "createSurface",
                JSONObject().put("surfaceId", surfaceId).put("catalogId", cat).put("sendDataModel", true)
            ),
            JSONObject().put("version", ver).put(
                "updateComponents",
                JSONObject().put("surfaceId", surfaceId).put("components", allComponents)
            ),
            JSONObject().put("version", ver).put(
                "updateDataModel",
                JSONObject().put("surfaceId", surfaceId).put("path", "/demo").put("value", demoData)
            )
        ).joinToString("\n") { it.toString() }
    }

    fun publishParameterizedFormToA2uiRegistry(
        template: ParameterizedDemoTemplate,
        surfaceId: String = "demo_form_${template.id}"
    ): String {
        val jsonl = toParameterizedA2uiJsonl(template, surfaceId)
        A2uiSurfaceRegistry.ingestJsonl(jsonl)
        return surfaceId
    }

    private fun classifySlot(
        step: RecordedUserStep,
        prevStep: RecordedUserStep?
    ): Triple<String, String, String> {
        val pkgLower = step.packageName.lowercase(Locale.ROOT)
        val sameAppNonTextPrev = prevStep != null &&
            prevStep.packageName == step.packageName &&
            !prevStep.eventType.equals("TEXT", ignoreCase = true)
        val combinedHints = if (sameAppNonTextPrev) {
            "${step.resourceId} ${prevStep!!.targetLabel} ${prevStep.resourceId}"
        } else {
            step.resourceId
        }
        val value = step.targetLabel.trim()

        if (DATE_LIKE_REGEX.containsMatchIn(value) || DATE_HINT_REGEX.containsMatchIn(combinedHints)) {
            return Triple("date", "날짜·시간", "예: 내일 오후 7시")
        }
        if (RECIPIENT_HINT_REGEX.containsMatchIn(combinedHints) ||
            (pkgLower.contains("kakao.talk") || pkgLower.contains("messaging") || pkgLower.contains("slack") || pkgLower.contains("telegram")) &&
            value.length <= 12 && !combinedHints.contains("message", ignoreCase = true) && !combinedHints.contains("input", ignoreCase = true)
        ) {
            return Triple("recipient", "받는 사람·대화방", "예: 김민수")
        }
        if (ITEM_HINT_REGEX.containsMatchIn(combinedHints) ||
            pkgLower.contains("coupang") || pkgLower.contains("sampleapp") ||
            pkgLower.contains("baemin") || pkgLower.contains("catchtable") || pkgLower.contains("korail")
        ) {
            return Triple("item", "상품·메뉴·장소", "예: 아이스 아메리카노")
        }
        return Triple("query", "검색어·입력값", "입력할 내용을 적어주세요")
    }
}
