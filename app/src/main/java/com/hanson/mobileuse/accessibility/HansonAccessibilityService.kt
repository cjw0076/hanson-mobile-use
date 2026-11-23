package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class BridgeTrafficEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val endpoint: String,
    val status: String
)

data class AccessibilityEngineState(
    val isConnected: Boolean = false,
    val activePackage: String = "",
    val activeClassName: String = "",
    val bridgePort: Int = ArtemisCommandServer.DEFAULT_PORT,
    val bridgeRunning: Boolean = false,
    val bridgeToken: String = "",
    val bridgeRequireToken: Boolean = true,
    val bridgeRequestCount: Int = 0,
    val recentBridgeTraffic: List<BridgeTrafficEntry> = emptyList(),
    val latestSnapshot: AtomicSnapshot? = null
)

/**
 * Tier 1 Core Engine: Combines Google Artemis Accessibility Helper (`CommandServer` + `HierarchyDumper`
 * + `GestureController`) with direct in-process APIs for the on-device Hanson Multimodal Agent.
 */
class HansonAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HansonA11yService"

        @Volatile
        var instance: HansonAccessibilityService? = null

        val uiChangeCounter = AtomicLong(0L)

        private val _engineState = MutableStateFlow(AccessibilityEngineState())
        val engineState: StateFlow<AccessibilityEngineState> = _engineState.asStateFlow()

        fun isServiceActive(): Boolean = instance != null && _engineState.value.isConnected

        fun initiateSelfHealing(context: android.content.Context): Boolean {
            return com.hanson.mobileuse.service.HansonForegroundService.initiateAccessibilitySelfHealing(context)
        }
    }

    val uiChangeCounter: AtomicLong
        get() = Companion.uiChangeCounter

    private var commandServer: ArtemisCommandServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "HansonAccessibilityService connected")

        val server = ArtemisCommandServer(
            service = this,
            port = ArtemisCommandServer.DEFAULT_PORT,
            requireTokenAuth = true
        ).apply {
            onBridgeEvent = { endpoint, status ->
                _engineState.update { prev ->
                    val updatedLog = (listOf(BridgeTrafficEntry(endpoint = endpoint, status = status)) + prev.recentBridgeTraffic)
                        .take(25)
                    prev.copy(
                        bridgeRequestCount = this.totalRequests.get(),
                        recentBridgeTraffic = updatedLog
                    )
                }
            }
            start()
        }
        commandServer = server

        _engineState.update {
            it.copy(
                isConnected = true,
                bridgePort = server.port,
                bridgeRunning = true,
                bridgeToken = server.sessionToken,
                bridgeRequireToken = server.requireTokenAuth
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val isChromeOrEdge = HierarchyDumper.isSystemChromeOrEdgePackage(pkg)

        if (!isChromeOrEdge && (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                )
        ) {
            Companion.uiChangeCounter.incrementAndGet()
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (pkg.isNotBlank() && !isChromeOrEdge && isApplicationWindowForEvent(event, pkg)) {
                _engineState.update {
                    it.copy(
                        activePackage = pkg,
                        activeClassName = cls
                    )
                }
            }
        }

        // "Show-Me-Once" (시연으로 가르치기) Live Demonstration Capture
        if (com.hanson.mobileuse.agent.UserDemonstrationRecorder.isRecording.value && pkg.isNotBlank()) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val src = event.source
                    val rect = android.graphics.Rect()
                    src?.getBoundsInScreen(rect)
                    val (screenW, screenH) = HierarchyDumper.getScreenDimensions(this)
                    val (normX, normY) = CoordinateNormalizer.pixelToNormalized(
                        rect.centerX().coerceAtLeast(0),
                        rect.centerY().coerceAtLeast(0),
                        screenW,
                        screenH
                    )
                    val label = event.text.joinToString(" ").ifBlank {
                        event.contentDescription?.toString().orEmpty().ifBlank {
                            src?.text?.toString().orEmpty()
                        }
                    }
                    val resId = src?.viewIdResourceName.orEmpty()
                    com.hanson.mobileuse.agent.UserDemonstrationRecorder.recordStep(
                        packageName = pkg,
                        eventType = if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) "TEXT" else "CLICK",
                        targetLabel = label,
                        resourceId = resId,
                        normX = normX,
                        normY = normY
                    )
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "HansonAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        commandServer?.stop()
        commandServer = null
        if (instance === this) {
            instance = null
        }
        _engineState.update {
            it.copy(
                isConnected = false,
                bridgeRunning = false
            )
        }
    }

    fun setBridgeRequireToken(enabled: Boolean) {
        commandServer?.requireTokenAuth = enabled
        _engineState.update { it.copy(bridgeRequireToken = enabled) }
    }

    private fun isApplicationWindowForEvent(event: AccessibilityEvent, pkg: String): Boolean {
        try {
            val srcWin = event.source?.window
            if (srcWin != null) {
                val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    srcWin.title?.toString().orEmpty()
                } else ""
                if (HierarchyDumper.isSystemChromeOrEdgePackage(pkg, title) ||
                    HierarchyDumper.isFloatingHudTitle(title)
                ) {
                    return false
                }
                return srcWin.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
        } catch (_: Throwable) {
        }
        val winId = event.windowId
        if (winId >= 0) {
            try {
                val matched = windows?.firstOrNull { it.id == winId }
                if (matched != null) {
                    val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        matched.title?.toString().orEmpty()
                    } else ""
                    if (HierarchyDumper.isSystemChromeOrEdgePackage(pkg, title) ||
                        HierarchyDumper.isFloatingHudTitle(title)
                    ) {
                        return false
                    }
                    return matched.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    fun captureSnapshotNow(includeScreenshot: Boolean = true): AtomicSnapshot {
        val snap = HierarchyDumper.captureAtomicSnapshot(
            service = this,
            includeScreenshot = includeScreenshot,
            filterInvisible = true
        )
        _engineState.update {
            val nextPkg = snap.activePackage.takeIf { p ->
                p.isNotBlank() && !HierarchyDumper.isSystemChromeOrEdgePackage(p)
            }
            it.copy(
                latestSnapshot = snap,
                activePackage = nextPkg ?: it.activePackage
            )
        }
        return snap
    }
}
