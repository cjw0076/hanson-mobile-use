package com.hanson.mobileuse.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * "Show-Me-Once" (시연으로 가르치기) Recorder:
 * Watches the user's real touches & text inputs across any app via `HansonAccessibilityService`
 * and automatically compiles the demonstration into a reusable UARP App Skill!
 */
object UserDemonstrationRecorder {

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
}
