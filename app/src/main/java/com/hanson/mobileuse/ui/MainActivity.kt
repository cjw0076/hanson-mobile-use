package com.hanson.mobileuse.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.MobileUseAgentEngine
import com.hanson.mobileuse.agent.PermissionHubManager
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import com.hanson.mobileuse.ui.screens.CommandCenterScreen
import com.hanson.mobileuse.ui.screens.SettingsScreen
import com.hanson.mobileuse.ui.theme.HansonObsidianTheme
import com.hanson.mobileuse.ui.theme.ObsidianColors

/**
 * Single-Surface Ahaboard Achromatic Activity.
 * Eliminates the 5-tab bottom navigation clutter.
 * Routine actions, permissions, and model/voice switches happen in 1 touch or via the agent.
 * Deep manual settings (`SettingsScreen`) are buried 2 levels deep (`⋯` -> `심층 엔진 설정 (고급)`).
 */
class MainActivity : ComponentActivity() {

    private lateinit var permissionHub: PermissionHubManager
    private lateinit var agentEngine: MobileUseAgentEngine

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHub.refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHub = HansonRuntimeHolder.getPermissionHub(applicationContext)
        agentEngine = HansonRuntimeHolder.getAgentEngine(applicationContext)

        HansonForegroundService.start(applicationContext)
        if (agentEngine.config.value.autoShowFloatingHud) {
            FloatingHudService.startIfPermitted(applicationContext)
        }

        setContent {
            HansonObsidianTheme {
                var showDeepSettings by rememberSaveable { mutableStateOf(false) }

                BackHandler(enabled = showDeepSettings) {
                    showDeepSettings = false
                }

                val a11yState by HansonAccessibilityService.engineState.collectAsState()
                val session by agentEngine.currentSession.collectAsState()
                val chatMessages by agentEngine.chatMessages.collectAsState()
                val config by agentEngine.config.collectAsState()
                val recentNotifications by RemoteInputSessionCache.recentNotifications.collectAsState()
                val isHudShowing by FloatingHudService.isShowing.collectAsState()
                val isDaemonRunning by HansonForegroundService.isRunning.collectAsState()

                Scaffold(
                    containerColor = ObsidianColors.Background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (showDeepSettings) {
                            SettingsScreen(
                                config = config,
                                isDaemonRunning = isDaemonRunning,
                                onSaveConfig = { key, model, maxIter, autoHud ->
                                    agentEngine.updateConfig(
                                        apiKey = key,
                                        modelName = model,
                                        maxIterations = maxIter,
                                        autoShowFloatingHud = autoHud
                                    )
                                    Toast.makeText(this@MainActivity, "엔진 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                },
                                onToggleDaemon = { run ->
                                    if (run) {
                                        HansonForegroundService.start(this@MainActivity)
                                    } else {
                                        HansonForegroundService.stop(this@MainActivity)
                                    }
                                },
                                onOneTouchAutoSetup = {
                                    triggerOneTouchSetup()
                                },
                                onClose = {
                                    showDeepSettings = false
                                }
                            )
                        } else {
                            CommandCenterScreen(
                                session = session,
                                chatMessages = chatMessages,
                                modelName = config.modelName,
                                ttsEnabled = config.ttsVoiceReply,
                                a11yState = a11yState,
                                notifications = recentNotifications,
                                onSendChatMessage = { msg -> agentEngine.sendChatMessage(msg) },
                                onStopAgent = { agentEngine.stopRun() },
                                onClearChat = { agentEngine.clearLiveChat() },
                                onToggleTts = {
                                    agentEngine.updateConfig(ttsVoiceReply = !config.ttsVoiceReply)
                                },
                                onToggleOverlayHud = { enable ->
                                    if (!enable || isHudShowing) {
                                        FloatingHudService.stop(this@MainActivity)
                                    } else {
                                        if (!FloatingHudService.startIfPermitted(this@MainActivity)) {
                                            triggerOneTouchSetup()
                                        }
                                    }
                                },
                                onOneTouchAutoSetup = {
                                    triggerOneTouchSetup()
                                },
                                onOpenDeepSettings = {
                                    showDeepSettings = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionHub.refreshStatus()
    }

    private fun triggerOneTouchSetup() {
        val perms = permissionHub.getAllRuntimePermissionsToRequest()
        if (perms.isNotEmpty()) {
            requestPermissionsLauncher.launch(perms)
        }
        agentEngine.runOneTouchAutoSetup()
        Toast.makeText(this, "원터치 환경 자동 구성을 실행했습니다.", Toast.LENGTH_SHORT).show()
    }
}
