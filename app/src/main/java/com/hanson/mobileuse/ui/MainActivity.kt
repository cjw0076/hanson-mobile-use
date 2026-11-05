package com.hanson.mobileuse.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.accessibility.GestureController
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.MobileUseAgentEngine
import com.hanson.mobileuse.agent.PermissionHubManager
import com.hanson.mobileuse.agent.PermissionStatusItem
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import com.hanson.mobileuse.ui.screens.CommandCenterScreen
import com.hanson.mobileuse.ui.screens.InspectorBridgeScreen
import com.hanson.mobileuse.ui.screens.PermissionsHubScreen
import com.hanson.mobileuse.ui.screens.SettingsScreen
import com.hanson.mobileuse.ui.screens.SkillsAndReceiptsScreen
import com.hanson.mobileuse.ui.theme.HansonObsidianTheme
import com.hanson.mobileuse.ui.theme.ObsidianColors
import java.util.Locale

private enum class NavTab(val labelKo: String, val icon: ImageVector) {
    COMMAND("라이브 채팅", Icons.Default.Bolt),
    PERMISSIONS("권한 허브", Icons.Default.Security),
    INSPECTOR("인스펙터", Icons.Default.AccountTree),
    SKILLS("스킬·영수증", Icons.Default.AutoAwesome),
    SETTINGS("설정", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var permissionHub: PermissionHubManager
    private lateinit var agentEngine: MobileUseAgentEngine
    private var voiceResultCallback: ((String) -> Unit)? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHub.refreshStatus()
        Toast.makeText(this, "런타임 권한 상태가 갱신되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private val voiceRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                voiceResultCallback?.invoke(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHub = HansonRuntimeHolder.getPermissionHub(applicationContext)
        agentEngine = HansonRuntimeHolder.getAgentEngine(applicationContext)

        // Start foreground watchdog daemon & auto-show floating live chat HUD if permitted
        HansonForegroundService.start(applicationContext)
        if (agentEngine.config.value.autoShowFloatingHud) {
            FloatingHudService.startIfPermitted(applicationContext)
        }

        setContent {
            HansonObsidianTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }

                val a11yState by HansonAccessibilityService.engineState.collectAsState()
                val permState by permissionHub.state.collectAsState()
                val session by agentEngine.currentSession.collectAsState()
                val chatMessages by agentEngine.chatMessages.collectAsState()
                val receipts by agentEngine.allReceipts.collectAsState()
                val config by agentEngine.config.collectAsState()
                val recentNotifications by RemoteInputSessionCache.recentNotifications.collectAsState()
                val isHudShowing by FloatingHudService.isShowing.collectAsState()
                val isDaemonRunning by HansonForegroundService.isRunning.collectAsState()

                Scaffold(
                    containerColor = ObsidianColors.Background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = ObsidianColors.Surface,
                            contentColor = ObsidianColors.TextPrimary
                        ) {
                            NavTab.entries.forEachIndexed { idx, tab ->
                                NavigationBarItem(
                                    selected = selectedTab == idx,
                                    onClick = {
                                        if (tab == NavTab.PERMISSIONS) {
                                            permissionHub.refreshStatus()
                                        }
                                        selectedTab = idx
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.labelKo
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.labelKo,
                                            fontSize = 11.sp,
                                            fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = ObsidianColors.Accent,
                                        selectedTextColor = ObsidianColors.Accent,
                                        indicatorColor = ObsidianColors.AccentDim,
                                        unselectedIconColor = ObsidianColors.TextSecondary,
                                        unselectedTextColor = ObsidianColors.TextSecondary
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (NavTab.entries[selectedTab]) {
                            NavTab.COMMAND -> CommandCenterScreen(
                                a11yState = a11yState,
                                permState = permState,
                                session = session,
                                chatMessages = chatMessages,
                                recentNotifications = recentNotifications,
                                isHudShowing = isHudShowing,
                                ttsEnabled = config.ttsVoiceReply,
                                onSendChatMessage = { msg -> agentEngine.sendChatMessage(msg) },
                                onStopGoal = { agentEngine.stopRun() },
                                onClearChat = { agentEngine.clearLiveChat() },
                                onApprovePending = { approved, alwaysAllow ->
                                    agentEngine.resolvePendingApproval(approved, alwaysAllow)
                                },
                                onQuickReplyNotification = { sessionId, msg ->
                                    val (_, resultMsg) = RemoteInputSessionCache.sendQuickReply(
                                        this@MainActivity,
                                        sessionId,
                                        msg
                                    )
                                    agentEngine.injectExternalReply(resultMsg, "QUICK REPLY")
                                    Toast.makeText(this@MainActivity, resultMsg, Toast.LENGTH_SHORT).show()
                                },
                                onToggleFloatingHud = {
                                    if (isHudShowing) {
                                        FloatingHudService.stop(this@MainActivity)
                                    } else {
                                        if (!FloatingHudService.startIfPermitted(this@MainActivity)) {
                                            selectedTab = NavTab.PERMISSIONS.ordinal
                                            Toast.makeText(
                                                this@MainActivity,
                                                "타 앱 위 그리기(SYSTEM_ALERT_WINDOW) 권한을 먼저 허용해주세요.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onToggleTts = {
                                    agentEngine.updateConfig(ttsVoiceReply = !config.ttsVoiceReply)
                                },
                                onLaunchVoiceInput = { callback ->
                                    launchVoiceRecognizer(callback)
                                },
                                onNavigateToPermissions = {
                                    permissionHub.refreshStatus()
                                    selectedTab = NavTab.PERMISSIONS.ordinal
                                }
                            )

                            NavTab.PERMISSIONS -> PermissionsHubScreen(
                                permState = permState,
                                onRequestAllRuntimePermissions = {
                                    requestPermissionsLauncher.launch(permissionHub.getAllRuntimePermissionsToRequest())
                                },
                                onRequestSinglePermissionItem = { item ->
                                    handlePermissionItemAction(item)
                                },
                                onToggleUnrestrictedMode = { enabled ->
                                    permissionHub.setUnrestrictedMode(enabled)
                                },
                                onCycleCapabilityGovernance = { cap ->
                                    permissionHub.cycleCapabilityLevel(cap)
                                },
                                onRefresh = {
                                    permissionHub.refreshStatus()
                                }
                            )

                            NavTab.INSPECTOR -> InspectorBridgeScreen(
                                a11yState = a11yState,
                                onCaptureSnapshot = {
                                    val svc = HansonAccessibilityService.instance
                                    if (svc == null) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "접근성 서비스가 꺼져 있습니다. [권한 허브]에서 활성화해주세요.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Thread {
                                            svc.captureSnapshotNow(includeScreenshot = true)
                                        }.start()
                                    }
                                },
                                onTestTapElement = { el ->
                                    val svc = HansonAccessibilityService.instance
                                    if (svc != null) {
                                        Thread {
                                            GestureController.tap(svc, el.centerX.toFloat(), el.centerY.toFloat())
                                        }.start()
                                        Toast.makeText(
                                            this@MainActivity,
                                            "[${el.index}] (${el.centerX}, ${el.centerY}) 탭 주입 완료",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onTriggerGlobalKey = { key ->
                                    val svc = HansonAccessibilityService.instance
                                    if (svc != null) {
                                        GestureController.performGlobalKey(svc, key)
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "접근성 서비스를 먼저 활성화해주세요.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onToggleBridgeTokenAuth = { enabled ->
                                    HansonAccessibilityService.instance?.setBridgeRequireToken(enabled)
                                },
                                onCopyText = { text ->
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("adb_cmd", text))
                                    Toast.makeText(this@MainActivity, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                                }
                            )

                            NavTab.SKILLS -> SkillsAndReceiptsScreen(
                                receipts = receipts,
                                onRunSkill = { skill ->
                                    selectedTab = NavTab.COMMAND.ordinal
                                    agentEngine.sendChatMessage(skill.goalPrompt)
                                },
                                onClearReceipts = {
                                    agentEngine.clearReceipts()
                                }
                            )

                            NavTab.SETTINGS -> SettingsScreen(
                                config = config,
                                isDaemonRunning = isDaemonRunning,
                                onSaveConfig = { key, model, maxIter, autoHud ->
                                    agentEngine.updateConfig(
                                        apiKey = key,
                                        modelName = model,
                                        maxIterations = maxIter,
                                        autoShowFloatingHud = autoHud
                                    )
                                    Toast.makeText(this@MainActivity, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                },
                                onToggleDaemon = { run ->
                                    if (run) {
                                        HansonForegroundService.start(this@MainActivity)
                                    } else {
                                        HansonForegroundService.stop(this@MainActivity)
                                    }
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

    private fun handlePermissionItemAction(item: PermissionStatusItem) {
        if (item.isRuntimePermission && item.runtimePermissions.isNotEmpty() && !item.isGranted) {
            requestPermissionsLauncher.launch(item.runtimePermissions.toTypedArray())
            return
        }
        try {
            val intent = permissionHub.buildSettingsIntent(item)
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchVoiceRecognizer(onResult: (String) -> Unit) {
        voiceResultCallback = onResult
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Hanson AI에게 수행할 모바일 작업을 말씀하세요")
            }
            voiceRecognizerLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "이 기기에서 음성 인식기를 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
