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
import android.widget.LinearLayout
import android.widget.TextView
import com.hanson.mobileuse.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Tier 2 Floating Live Agent HUD Overlay (`SYSTEM_ALERT_WINDOW`).
 * Renders a draggable Obsidian pill on top of all Android apps so the user can monitor
 * the AI agent's current sub-goal/action and trigger a 1-tap Emergency Stop at any moment.
 */
class FloatingHudService : Service() {

    companion object {
        private val _isShowing = MutableStateFlow(false)
        val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

        private var instance: FloatingHudService? = null
        var onStopClicked: (() -> Unit)? = null

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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var dotView: View? = null
    private var stopButton: TextView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createOverlayView()
        _isShowing.value = true
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

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#E60D0F14"))
                setStroke(dp(1), Color.parseColor("#666C8EFF"))
            }
        }

        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6C8EFF"))
            }
        }
        dotView = statusDot
        container.addView(statusDot)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val titleTv = TextView(this).apply {
            text = "Hanson Mobile-Use HUD"
            setTextColor(Color.parseColor("#F1F3F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        titleView = titleTv

        val subTv = TextView(this).apply {
            text = "대기 중 · 드래그하여 이동"
            setTextColor(Color.parseColor("#9BA1B0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
        }
        subtitleView = subTv

        textColumn.addView(titleTv)
        textColumn.addView(subTv)
        container.addView(textColumn)

        val stopBtn = TextView(this).apply {
            text = "정지"
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F87171"))
            }
            setOnClickListener {
                onStopClicked?.invoke()
                postStatusUpdate("긴급 정지됨", "사용자 요청으로 중단", false)
            }
        }
        stopButton = stopBtn
        container.addView(stopBtn)

        val openBtn = TextView(this).apply {
            text = "열기"
            setTextColor(Color.parseColor("#6C8EFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(5), dp(6), dp(5))
            setOnClickListener {
                val intent = Intent(this@FloatingHudService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }
        container.addView(openBtn)

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
            y = dp(96)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
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
                            wm.updateViewLayout(container, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(container, params)
            rootView = container
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
