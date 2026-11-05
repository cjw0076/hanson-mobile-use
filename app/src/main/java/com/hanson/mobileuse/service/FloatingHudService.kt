package com.hanson.mobileuse.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Tier 2 Expandable Floating Live Chat & Agent HUD Overlay (`SYSTEM_ALERT_WINDOW`).
 * Can stay as a compact draggable pill OR expand into an interactive Live Chat window
 * over any app (KakaoTalk, YouTube, Browser, etc.) with keyboard input and quick actions.
 */
class FloatingHudService : Service() {

    companion object {
        private val _isShowing = MutableStateFlow(false)
        val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

        private var instance: FloatingHudService? = null
        var onStopClicked: (() -> Unit)? = null
        var onSendLiveChatMessage: ((String) -> Unit)? = null

        fun startIfPermitted(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            context.startService(Intent(context, FloatingHudService::class.java))
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingHudService::class.java))
        }

        fun updateHudStatus(title: String, subtitle: String, isRunning: Boolean) {
            instance?.postStatusUpdate(title, subtitle, isRunning)
        }

        fun notifyChatUpdated(messages: List<LiveChatMessage>) {
            instance?.postChatMessagesUpdate(messages)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rootView: View? = null

    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var dotView: View? = null
    private var stopButton: TextView? = null
    private var toggleChatButton: TextView? = null

    private var expandedChatContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var chatListLayout: LinearLayout? = null
    private var chatInputEdit: EditText? = null
    private var isChatExpanded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createOverlayView()
        _isShowing.value = true

        // Sync initial chat messages from runtime holder
        val engine = HansonRuntimeHolder.getAgentEngine(applicationContext)
        postChatMessagesUpdate(engine.chatMessages.value)
    }

    override fun onDestroy() {
        removeOverlayView()
        if (instance === this) instance = null
        _isShowing.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun postStatusUpdate(title: String, subtitle: String, isRunning: Boolean) {
        mainHandler.post {
            titleView?.text = title
            subtitleView?.text = subtitle
            val dotColor = if (isRunning) Color.parseColor("#34D399") else Color.parseColor("#6C8EFF")
            (dotView?.background as? GradientDrawable)?.setColor(dotColor)
            stopButton?.visibility = if (isRunning) View.VISIBLE else View.GONE
        }
    }

    fun postChatMessagesUpdate(messages: List<LiveChatMessage>) {
        mainHandler.post {
            val listLayout = chatListLayout ?: return@post
            listLayout.removeAllViews()
            val density = resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            messages.takeLast(12).forEach { msg ->
                val isUser = msg.sender == ChatSender.USER
                val bubble = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(6)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        if (isUser) {
                            setColor(Color.parseColor("#2E6C8EFF"))
                            setStroke(dp(1), Color.parseColor("#886C8EFF"))
                        } else {
                            setColor(Color.parseColor("#FF14161D"))
                            setStroke(dp(1), Color.parseColor("#26FFFFFF"))
                        }
                    }
                }

                val prefix = when (msg.sender) {
                    ChatSender.USER -> "나"
                    ChatSender.AGENT -> msg.toolBadge ?: "Hanson AI"
                    ChatSender.ACTION -> msg.toolBadge ?: "ACTION"
                    ChatSender.NOTIFICATION -> "알림 · ${msg.toolBadge ?: "RemoteInput"}"
                }

                val headerTv = TextView(this).apply {
                    text = prefix
                    setTextColor(if (isUser) Color.parseColor("#6C8EFF") else Color.parseColor("#34D399"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                val bodyTv = TextView(this).apply {
                    text = msg.text
                    setTextColor(Color.parseColor("#F1F3F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                }
                bubble.addView(headerTv)
                bubble.addView(bodyTv)
                listLayout.addView(bubble)
            }
            chatScrollView?.post {
                chatScrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun setChatExpanded(expanded: Boolean) {
        isChatExpanded = expanded
        expandedChatContainer?.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleChatButton?.text = if (expanded) "접기" else "채팅"

        val wm = windowManager ?: return
        val root = rootView ?: return
        val params = layoutParams ?: return

        // Toggle FLAG_NOT_FOCUSABLE so EditText can receive soft keyboard input when expanded!
        params.flags = if (expanded) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        try {
            wm.updateViewLayout(root, params)
            if (!expanded) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(root.windowToken, 0)
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val outerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F20D0F14"))
                setStroke(dp(1), Color.parseColor("#806C8EFF"))
            }
        }

        // Top Pill Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34D399"))
            }
        }
        dotView = statusDot
        headerRow.addView(statusDot)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val titleTv = TextView(this).apply {
            text = "Hanson Live Chat HUD"
            setTextColor(Color.parseColor("#F1F3F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        titleView = titleTv

        val subTv = TextView(this).apply {
            text = "라이브 세션 활성 · 탭하여 채팅"
            setTextColor(Color.parseColor("#9BA1B0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        }
        subtitleView = subTv

        textColumn.addView(titleTv)
        textColumn.addView(subTv)
        headerRow.addView(textColumn)

        val stopBtn = TextView(this).apply {
            text = "정지"
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#F87171"))
            }
            setOnClickListener {
                onStopClicked?.invoke()
            }
        }
        stopButton = stopBtn
        headerRow.addView(stopBtn)

        val chatToggleBtn = TextView(this).apply {
            text = "채팅"
            setTextColor(Color.parseColor("#06070B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#6C8EFF"))
            }
            setOnClickListener {
                setChatExpanded(!isChatExpanded)
            }
        }
        toggleChatButton = chatToggleBtn
        headerRow.addView(chatToggleBtn)

        val openBtn = TextView(this).apply {
            text = "앱"
            setTextColor(Color.parseColor("#6C8EFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(7), dp(4), dp(4), dp(4))
            setOnClickListener {
                val intent = Intent(this@FloatingHudService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }
        headerRow.addView(openBtn)
        outerCard.addView(headerRow)

        // Expandable Live Chat Drawer Container
        val chatDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        expandedChatContainer = chatDrawer

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            )
        }
        chatScrollView = scroll

        val msgList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        chatListLayout = msgList
        scroll.addView(msgList)
        chatDrawer.addView(scroll)

        // Input Row inside Overlay
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }

        val edit = EditText(this).apply {
            hint = "타 앱 위에서 실시간 명령/질문 입력..."
            setHintTextColor(Color.parseColor("#636978"))
            setTextColor(Color.parseColor("#F1F3F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1A1E28"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        chatInputEdit = edit
        inputRow.addView(edit)

        val sendBtn = TextView(this).apply {
            text = "전송"
            setTextColor(Color.parseColor("#06070B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#34D399"))
            }
            setOnClickListener {
                val msg = edit.text?.toString()?.trim().orEmpty()
                if (msg.isNotEmpty()) {
                    edit.setText("")
                    val engine = HansonRuntimeHolder.getAgentEngine(applicationContext)
                    engine.sendChatMessage(msg)
                }
            }
        }
        inputRow.addView(sendBtn)
        chatDrawer.addView(inputRow)
        outerCard.addView(chatDrawer)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(92)
        }
        layoutParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(outerCard, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(outerCard, params)
            rootView = outerCard
        } catch (_: Exception) {
        }
    }

    private fun removeOverlayView() {
        val view = rootView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        } finally {
            rootView = null
        }
    }
}
