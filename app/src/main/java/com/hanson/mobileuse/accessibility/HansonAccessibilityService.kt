package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val bridgeRequireToken: Boolean = false,
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
            private set

        private val _engineState = MutableStateFlow(AccessibilityEngineState())
        val engineState: StateFlow<AccessibilityEngineState> = _engineState.asStateFlow()

        fun isServiceActive(): Boolean = instance != null && _engineState.value.isConnected
    }

    private var commandServer: ArtemisCommandServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "HansonAccessibilityService connected")

        val server = ArtemisCommandServer(
            service = this,
            port = ArtemisCommandServer.DEFAULT_PORT,
            requireTokenAuth = false
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val pkg = event.packageName?.toString().orEmpty()
            val cls = event.className?.toString().orEmpty()
            if (pkg.isNotBlank()) {
                _engineState.update {
                    it.copy(
                        activePackage = pkg,
                        activeClassName = cls
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

    fun captureSnapshotNow(includeScreenshot: Boolean = true): AtomicSnapshot {
        val snap = HierarchyDumper.captureAtomicSnapshot(
            service = this,
            includeScreenshot = includeScreenshot,
            filterInvisible = true
        )
        _engineState.update {
            it.copy(
                latestSnapshot = snap,
                activePackage = snap.activePackage.ifBlank { it.activePackage }
            )
        }
        return snap
    }
}
