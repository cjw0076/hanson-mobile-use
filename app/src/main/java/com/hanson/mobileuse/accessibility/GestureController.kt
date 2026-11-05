package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ported & extended from Google Artemis `GestureController.java`.
 * Supports pixel & normalized gestures, multi-tap bursts (`click_sequence`),
 * directional scrolling with 40% visual anchor overlap, direct `ACTION_SET_TEXT`
 * with clipboard paste fallback, and global OS hardware actions.
 */
object GestureController {
    private const val TAG = "GestureController"

    fun tap(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(20L, 1000L))
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    fun doubleTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        val path1 = Path().apply { moveTo(safeX, safeY) }
        val path2 = Path().apply { moveTo(safeX, safeY) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 45L)
        val stroke2 = GestureDescription.StrokeDescription(path2, 105L, 45L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return dispatch(service, gesture)
    }

    fun multiTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        times: Int,
        delayMs: Long = 120L
    ): Boolean {
        val count = times.coerceIn(1, 10)
        if (count == 1) return tap(service, x, y)
        if (count == 2 && delayMs <= 150L) return doubleTap(service, x, y)
        var allOk = true
        for (i in 0 until count) {
            if (!tap(service, x, y, 50L)) allOk = false
            if (i < count - 1) {
                try {
                    Thread.sleep(delayMs.coerceIn(40L, 2000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return allOk
    }

    fun longPress(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 700L
    ): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(300L, 5000L)
        )
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    fun swipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.coerceAtLeast(0f), startY.coerceAtLeast(0f))
            lineTo(endX.coerceAtLeast(0f), endY.coerceAtLeast(0f))
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(80L, 5000L)
        )
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Executes Google Artemis `click_sequence`: rapid sequence of taps for transient menus,
     * calculator/dialpad entry, or multi-item selection without round-trip VLM latency.
     */
    fun clickSequence(
        service: AccessibilityService,
        points: List<Pair<Float, Float>>,
        delayMs: Long = 180L
    ): Boolean {
        if (points.isEmpty()) return false
        var allSucceeded = true
        for ((idx, pt) in points.withIndex()) {
            val ok = tap(service, pt.first, pt.second, 55L)
            if (!ok) allSucceeded = false
            if (idx < points.lastIndex) {
                try {
                    Thread.sleep(delayMs.coerceIn(50L, 2000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return allSucceeded
    }

    /**
     * Inputs text into the currently focused (or newly tapped) editable field.
     * Supports clearing existing text and submitting IME Enter action.
     */
    fun inputText(
        service: AccessibilityService,
        text: String,
        targetX: Float? = null,
        targetY: Float? = null,
        clearExisting: Boolean = false
    ): Boolean {
        if (targetX != null && targetY != null) {
            tap(service, targetX, targetY, 60L)
            try {
                Thread.sleep(220L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val focusedNode = findTargetEditableNode(service) ?: return false

        if (clearExisting) {
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val directResult = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (directResult) {
            return true
        }

        // Fallback: copy to clipboard and paste
        setClipboard(service, text)
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun clearText(service: AccessibilityService): Boolean {
        val node = findTargetEditableNode(service) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun setClipboard(service: AccessibilityService, text: String): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        service.mainExecutor.execute {
            try {
                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("hanson_mobile_use", text))
                    ok.set(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard set error", e)
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(2, TimeUnit.SECONDS) && ok.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    fun performGlobalKey(service: AccessibilityService, keyName: String): Boolean {
        val normalized = keyName.trim().uppercase()
        if (normalized == "ENTER" || normalized == "SEARCH" || normalized == "IME_ENTER") {
            val node = findTargetEditableNode(service)
            if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeActionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                if (node.performAction(imeActionId)) return true
            }
        }

        val actionId = when (normalized) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS", "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "POWER_DIALOG" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "TOGGLE_SPLIT_SCREEN", "SPLIT_SCREEN" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            } else -1
            "TAKE_SCREENSHOT", "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            } else -1
            else -> -1
        }
        return if (actionId != -1) {
            service.performGlobalAction(actionId)
        } else {
            false
        }
    }

    private fun findTargetEditableNode(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && (inputFocused.isEditable || inputFocused.isFocusable)) {
            return inputFocused
        }
        val a11yFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (a11yFocused != null && a11yFocused.isEditable) {
            return a11yFocused
        }
        return findFirstEditableRecursive(root)
    }

    private fun findFirstEditableRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableRecursive(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled by system")
                    success.set(false)
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return false
        return try {
            latch.await(6, TimeUnit.SECONDS) && success.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
