package com.hanson.mobileuse.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hanson.mobileuse.agent.A2uiJsonCodec
import com.hanson.mobileuse.agent.A2uiSurfaceRegistry
import com.hanson.mobileuse.agent.CoDrawingCanvasEngine
import com.hanson.mobileuse.agent.CrossAppOverlayMode
import com.hanson.mobileuse.agent.DynamicUiEventBus
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.MobileUseAgentEngine
import com.hanson.mobileuse.ui.components.A2uiSurfaceCard
import com.hanson.mobileuse.ui.components.CoDrawingInteractiveCanvas
import com.hanson.mobileuse.ui.components.CoDrawingToolbarPill
import com.hanson.mobileuse.ui.components.CrossAppCoDrawControlPill
import com.hanson.mobileuse.ui.components.SseLiveDynamicUiSurface
import com.hanson.mobileuse.ui.theme.HansonObsidianTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Lifecycle / SavedState / ViewModelStore owner bridge enabling real Jetpack Compose (`ComposeView`)
 * rendering inside a non-Activity `SYSTEM_ALERT_WINDOW` Android [Service].
 */
internal class OverlayComposeLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun attachToView(view: android.view.View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun onCreateAndResume() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        runCatching {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
        store.clear()
    }
}

/**
 * Out-of-App Cross-App Jetpack Compose Overlay (`SYSTEM_ALERT_WINDOW`) for:
 * 1. Real-time SSE Dynamic UI cards floating cleanly over KakaoTalk, Chrome, YouTube, or any app.
 * 2. Human + AI Co-Drawing ("함께 그리기") directly over another app's live UI with instant
 *    2-layer touch isolation (`PASSTHROUGH_WATCH` ↔ `CO_DRAW_ACTIVE` + always-touchable control pill).
 *
 * Security & Safety Invariants:
 * - Declared `android:exported="false"` in `AndroidManifest.xml`.
 * - Verifies `Settings.canDrawOverlays(context)` before attaching any window.
 * - Immediately yields to `FLAG_NOT_TOUCHABLE` during `FloatingHudService.setPassThroughTemporarily(true)`
 *   so autonomous `GestureController` taps pass directly to the target app.
 */
class DynamicCanvasOverlayService : Service() {

    companion object {
        private const val TAG = "DynamicCanvasOverlay"

        private val _isOverlayActive = MutableStateFlow(false)
        val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

        @Volatile
        private var instance: DynamicCanvasOverlayService? = null

        @Volatile
        var onAskWithCoDrawSketch: ((String) -> Unit)? = null

        fun startIfPermitted(context: Context, initialDrawMode: Boolean = false): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            CoDrawingCanvasEngine.setOverlayMode(
                if (initialDrawMode) CrossAppOverlayMode.CO_DRAW_ACTIVE else CrossAppOverlayMode.PASSTHROUGH_WATCH
            )
            return try {
                context.startService(Intent(context, DynamicCanvasOverlayService::class.java))
                true
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "ForegroundServiceStartNotAllowedException: ${e.message}")
                } else {
                    Log.w(TAG, "IllegalStateException starting overlay: ${e.message}")
                }
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException starting overlay: ${e.message}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "Failed starting overlay: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DynamicCanvasOverlayService::class.java))
            } catch (_: Exception) {
            }
        }

        /** Called strictly during atomic gesture dispatch so taps never strike our overlay windows. */
        fun setGesturePassThrough(enabled: Boolean) {
            instance?.applyGestureYield(enabled)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var canvasLifecycleOwner: OverlayComposeLifecycleOwner? = null
    private var controlLifecycleOwner: OverlayComposeLifecycleOwner? = null

    private var canvasComposeView: ComposeView? = null
    private var canvasLayoutParams: WindowManager.LayoutParams? = null

    private var controlComposeView: ComposeView? = null
    private var controlLayoutParams: WindowManager.LayoutParams? = null

    @Volatile
    private var isGestureYielding: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!Settings.canDrawOverlays(this)) {
            _isOverlayActive.value = false
            stopSelf()
            return
        }

        val attached = attachDualComposeWindows()
        if (!attached) {
            _isOverlayActive.value = false
            stopSelf()
            return
        }
        _isOverlayActive.value = true

        // Reactively sync Window 1 touchability whenever CrossAppOverlayMode changes
        serviceScope.launch {
            CoDrawingCanvasEngine.state.collect { state ->
                syncCanvasWindowTouchability(state.overlayMode)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        detachDualComposeWindows()
        if (instance === this) instance = null
        _isOverlayActive.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachDualComposeWindows(): Boolean {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val density = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).roundToInt()

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Layer 1: Full-Screen Transparent Co-Draw Canvas + Floating SSE Dynamic UI Card
            val cOwner = OverlayComposeLifecycleOwner()
            canvasLifecycleOwner = cOwner
            val cView = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                cOwner.attachToView(this)
                setContent {
                    HansonObsidianTheme {
                        val drawState by CoDrawingCanvasEngine.state.collectAsState()
                        val sseState by DynamicUiEventBus.surfaceState.collectAsState()
                        val a2uiSurfaces by A2uiSurfaceRegistry.surfaces.collectAsState()
                        val isDrawMode = drawState.overlayMode == CrossAppOverlayMode.CO_DRAW_ACTIVE

                        Box(modifier = Modifier.fillMaxSize()) {
                            CoDrawingInteractiveCanvas(
                                canvasState = drawState,
                                isInteractive = isDrawMode,
                                showDotGrid = false,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Bottom-docked interactive tool strip ONLY when drawing mode is active
                            if (isDrawMode) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 18.dp)
                                ) {
                                    CoDrawingToolbarPill(
                                        canvasState = drawState,
                                        onSubmitToAi = {
                                            CoDrawingCanvasEngine.setOverlayMode(CrossAppOverlayMode.PASSTHROUGH_WATCH)
                                            dispatchAskWithSketch()
                                        }
                                    )
                                }
                            }

                            // Floating SSE Dynamic UI & A2UI Surfaces (anchored near bottom above toolbar, collapsible)
                            if (drawState.showFloatingDynamicCard) {
                                val renderableA2ui = a2uiSurfaces.values
                                    .filter { it.isReady && it.hasRootComponent }
                                    .takeLast(2)
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(
                                            start = 12.dp,
                                            end = 12.dp,
                                            bottom = if (isDrawMode) 68.dp else 20.dp
                                        )
                                ) {
                                    renderableA2ui.forEach { a2uiSurface ->
                                        A2uiSurfaceCard(
                                            surface = a2uiSurface,
                                            compactOverlayMode = true,
                                            onUserAction = { userAction ->
                                                if (MobileUseAgentEngine.isSupportedA2uiSecretaryAction(userAction.name)) {
                                                    val ctxJson = A2uiJsonCodec.mapToJsonObject(userAction.context)
                                                    serviceScope.launch(Dispatchers.IO) {
                                                        val engine = HansonRuntimeHolder.getAgentEngine(applicationContext)
                                                        engine.handleA2uiSecretaryAction(userAction.name, ctxJson)
                                                    }
                                                } else {
                                                    when (userAction.name) {
                                                        "open_citation", "open_url" -> {
                                                            val rawUrl = userAction.context["url"]?.toString().orEmpty()
                                                            openExternalHttpsUrl(rawUrl)
                                                        }
                                                        "speak_board", "speak" -> {
                                                            val spoken = userAction.context["text"]?.toString()?.trim().orEmpty()
                                                            if (spoken.isNotBlank()) {
                                                                LiveVoiceSessionManager.speakAgentResponse(spoken)
                                                            }
                                                        }
                                                        else -> {
                                                            val engine = HansonRuntimeHolder.getAgentEngine(applicationContext)
                                                            engine.sendChatMessage(userAction.toHumanPromptSummary())
                                                        }
                                                    }
                                                }
                                            },
                                            onDismiss = {
                                                A2uiSurfaceRegistry.deleteSurface(a2uiSurface.surfaceId)
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                    SseLiveDynamicUiSurface(
                                        surfaceState = sseState,
                                        compactOverlayMode = true,
                                        onCitationClick = { url -> openExternalHttpsUrl(url) },
                                        onSpeakText = { spoken -> LiveVoiceSessionManager.speakAgentResponse(spoken) },
                                        onAskAboutItem = { q ->
                                            val engine = HansonRuntimeHolder.getAgentEngine(applicationContext)
                                            engine.sendChatMessage(q)
                                        },
                                        onOpenCoDraw = {
                                            CoDrawingCanvasEngine.setOverlayMode(CrossAppOverlayMode.CO_DRAW_ACTIVE)
                                        },
                                        onDismiss = {
                                            CoDrawingCanvasEngine.setFloatingDynamicCardVisible(false)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }
            }
            cOwner.onCreateAndResume()

            val initialDrawMode = CoDrawingCanvasEngine.state.value.overlayMode == CrossAppOverlayMode.CO_DRAW_ACTIVE
            val initialCanvasFlags = if (initialDrawMode) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            val cParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                initialCanvasFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            wm.addView(cView, cParams)
            canvasComposeView = cView
            canvasLayoutParams = cParams

            // Layer 2: Always-Touchable Compact Draggable Control Pill
            val pOwner = OverlayComposeLifecycleOwner()
            controlLifecycleOwner = pOwner
            val pParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(44)
            }

            val pView = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                pOwner.attachToView(this)
                setContent {
                    HansonObsidianTheme {
                        val drawState by CoDrawingCanvasEngine.state.collectAsState()
                        val sseState by DynamicUiEventBus.surfaceState.collectAsState()

                        CrossAppCoDrawControlPill(
                            canvasState = drawState,
                            sseState = sseState,
                            onToggleMode = { CoDrawingCanvasEngine.toggleOverlayMode() },
                            onToggleCard = {
                                CoDrawingCanvasEngine.setFloatingDynamicCardVisible(!drawState.showFloatingDynamicCard)
                            },
                            onAskWithSketch = {
                                CoDrawingCanvasEngine.setOverlayMode(CrossAppOverlayMode.PASSTHROUGH_WATCH)
                                dispatchAskWithSketch()
                            },
                            onClear = { CoDrawingCanvasEngine.clear() },
                            onClose = { stopSelf() },
                            modifier = Modifier.pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val lp = controlLayoutParams ?: return@detectDragGestures
                                    val view = controlComposeView ?: return@detectDragGestures
                                    lp.x += dragAmount.x.roundToInt()
                                    lp.y = (lp.y + dragAmount.y.roundToInt()).coerceAtLeast(0)
                                    runCatching { windowManager?.updateViewLayout(view, lp) }
                                }
                            }
                        )
                    }
                }
            }
            pOwner.onCreateAndResume()

            wm.addView(pView, pParams)
            controlComposeView = pView
            controlLayoutParams = pParams
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed attaching dual Compose overlay windows: ${e.message}", e)
            detachDualComposeWindows()
            false
        }
    }

    private fun detachDualComposeWindows() {
        val wm = windowManager
        controlComposeView?.let { v ->
            runCatching { wm?.removeView(v) }
        }
        canvasComposeView?.let { v ->
            runCatching { wm?.removeView(v) }
        }
        controlLifecycleOwner?.onDestroy()
        canvasLifecycleOwner?.onDestroy()
        controlComposeView = null
        canvasComposeView = null
        controlLayoutParams = null
        canvasLayoutParams = null
        controlLifecycleOwner = null
        canvasLifecycleOwner = null
        windowManager = null
    }

    private fun syncCanvasWindowTouchability(mode: CrossAppOverlayMode) {
        mainHandler.post {
            if (isGestureYielding) return@post
            val wm = windowManager ?: return@post
            val lp = canvasLayoutParams ?: return@post
            val view = canvasComposeView ?: return@post

            val shouldBeTouchable = (mode == CrossAppOverlayMode.CO_DRAW_ACTIVE)
            val nextFlags = if (shouldBeTouchable) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (lp.flags != nextFlags || view.alpha < 0.99f) {
                lp.flags = nextFlags
                view.alpha = 1.0f
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
    }

    private fun applyGestureYield(yielding: Boolean) {
        isGestureYielding = yielding
        mainHandler.post {
            val wm = windowManager ?: return@post
            val cLp = canvasLayoutParams
            val cView = canvasComposeView
            if (cLp != null && cView != null) {
                val nextFlags = if (yielding) {
                    cLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    val isDraw = CoDrawingCanvasEngine.state.value.overlayMode == CrossAppOverlayMode.CO_DRAW_ACTIVE
                    if (isDraw) {
                        cLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    } else {
                        cLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    }
                }
                cLp.flags = nextFlags
                cView.alpha = if (yielding) 0.25f else 1.0f
                runCatching { wm.updateViewLayout(cView, cLp) }
            }

            val pLp = controlLayoutParams
            val pView = controlComposeView
            if (pLp != null && pView != null) {
                pLp.flags = if (yielding) {
                    pLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    pLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                pView.alpha = if (yielding) 0.35f else 1.0f
                runCatching { wm.updateViewLayout(pView, pLp) }
            }
        }
    }

    private fun dispatchAskWithSketch() {
        val defaultQuestion = if (CoDrawingCanvasEngine.state.value.humanElementCount > 0) {
            "방금 화면 위에 표시한 부분을 설명하고 다음으로 눌러야 할 곳을 함께 표시해줘"
        } else {
            "현재 화면을 분석하고 핵심 포인트를 보드와 화살표로 안내해줘"
        }
        val callback = onAskWithCoDrawSketch
        if (callback != null) {
            callback(defaultQuestion)
        } else {
            val engine = HansonRuntimeHolder.getAgentEngine(applicationContext)
            engine.submitCoDrawingQuery(defaultQuestion)
        }
    }

    private fun openExternalHttpsUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
}
