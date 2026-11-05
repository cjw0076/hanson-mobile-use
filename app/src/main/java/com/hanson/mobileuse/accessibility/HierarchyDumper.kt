package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.util.Xml
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

data class AtomicSnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val activePackage: String = "",
    val activeWindowTitle: String = "",
    val windowCount: Int = 0,
    val rootNodes: List<A11yNode> = emptyList(),
    val indexedElements: List<IndexedUiElement> = emptyList(),
    val screenshotBase64Jpeg: String? = null,
    val screenshotError: String? = null
) {
    fun toPromptContext(maxElements: Int = 100): String {
        val sb = StringBuilder()
        sb.appendLine("Screen Size: ${screenWidth}x${screenHeight} px (Normalized Grid: 0..1000 x 0..1000)")
        sb.appendLine("Active Package: ${activePackage.ifBlank { "unknown" }}")
        if (activeWindowTitle.isNotBlank()) {
            sb.appendLine("Active Window Title: $activeWindowTitle")
        }
        sb.appendLine("Windows Scanned: $windowCount | Interactive/Informative Elements: ${indexedElements.size}")
        sb.appendLine("Visible UI Elements:")
        val slice = indexedElements.take(maxElements)
        if (slice.isEmpty()) {
            sb.appendLine("  (No accessibility elements exposed by current window; rely on screenshot coordinates [normX, normY] in 0..1000 range)")
        } else {
            for (el in slice) {
                sb.appendLine("  ${el.toPromptLine()}")
            }
            if (indexedElements.size > maxElements) {
                sb.appendLine("  ... (${indexedElements.size - maxElements} more elements omitted)")
            }
        }
        return sb.toString()
    }

    fun toJson(includeFullTree: Boolean = false): JSONObject = JSONObject().apply {
        put("timestamp", timestampMs)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("activePackage", activePackage)
        put("activeWindowTitle", activeWindowTitle)
        put("windowCount", windowCount)
        put("elementCount", indexedElements.size)
        val elArr = JSONArray()
        indexedElements.forEach { elArr.put(it.toJson()) }
        put("elements", elArr)
        if (includeFullTree) {
            val winArr = JSONArray()
            rootNodes.forEach { winArr.put(it.toJson(screenWidth, screenHeight)) }
            put("windows", winArr)
        }
        if (screenshotBase64Jpeg != null) {
            put("screenshot", screenshotBase64Jpeg)
        }
        if (screenshotError != null) {
            put("screenshotError", screenshotError)
        }
    }
}

/**
 * Ported & enhanced from Google Artemis `HierarchyDumper.java`.
 * Performs multi-window Z-layer UI tree extraction with scrollable parent clipping,
 * API 33+ hybrid descendant prefetching, and synchronized hardware screenshot capture.
 */
object HierarchyDumper {
    private const val TAG = "HierarchyDumper"
    private const val ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3

    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            }
            val res = context.resources.displayMetrics
            res.widthPixels.coerceAtLeast(1080) to res.heightPixels.coerceAtLeast(2400)
        } catch (e: Exception) {
            1080 to 2400
        }
    }

    fun captureAtomicSnapshot(
        service: AccessibilityService,
        includeScreenshot: Boolean = true,
        filterInvisible: Boolean = true,
        jpegQuality: Int = 78
    ): AtomicSnapshot {
        val (screenW, screenH) = getScreenDimensions(service)
        val screenBounds = Rect(0, 0, screenW, screenH)

        val windows = try {
            service.windows
        } catch (e: Exception) {
            null
        }

        val rootNodes = mutableListOf<A11yNode>()
        val indexedElements = mutableListOf<IndexedUiElement>()
        val indexCounter = AtomicInteger(0)
        var activePkg = ""
        var activeTitle = ""
        var scannedWindows = 0

        if (!windows.isNullOrEmpty()) {
            // Sort by layer descending (top-most window first)
            val sortedWindows = windows.sortedByDescending { it.layer }
            scannedWindows = sortedWindows.size
            for (win in sortedWindows) {
                val root = getWindowRootCompat(win) ?: continue
                val winType = windowTypeToString(win.type)
                val winTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    win.title?.toString().orEmpty()
                } else ""
                if (activePkg.isBlank() && win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    activePkg = root.packageName?.toString().orEmpty()
                    activeTitle = winTitle
                }
                val windowClip = Rect(screenBounds)
                val winBounds = Rect()
                win.getBoundsInScreen(winBounds)
                if (!winBounds.isEmpty) {
                    windowClip.intersect(winBounds)
                }

                val dumpedRoot = dumpNodeRecursive(
                    node = root,
                    filterInvisible = filterInvisible,
                    clipBounds = windowClip,
                    screenW = screenW,
                    screenH = screenH,
                    windowLayer = win.layer,
                    windowType = winType,
                    windowTitle = winTitle,
                    indexCounter = indexCounter,
                    indexedOut = indexedElements
                )
                if (dumpedRoot != null) {
                    rootNodes.add(dumpedRoot)
                }
            }
        } else {
            val activeRoot = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            if (activeRoot != null) {
                scannedWindows = 1
                activePkg = activeRoot.packageName?.toString().orEmpty()
                val dumpedRoot = dumpNodeRecursive(
                    node = activeRoot,
                    filterInvisible = filterInvisible,
                    clipBounds = screenBounds,
                    screenW = screenW,
                    screenH = screenH,
                    windowLayer = 0,
                    windowType = "APPLICATION",
                    windowTitle = "",
                    indexCounter = indexCounter,
                    indexedOut = indexedElements
                )
                if (dumpedRoot != null) {
                    rootNodes.add(dumpedRoot)
                }
            }
        }

        var screenshotBase64: String? = null
        var screenshotErr: String? = null
        if (includeScreenshot) {
            val (b64, err) = captureScreenshotBase64(service, jpegQuality)
            screenshotBase64 = b64
            screenshotErr = err
        }

        return AtomicSnapshot(
            timestampMs = System.currentTimeMillis(),
            screenWidth = screenW,
            screenHeight = screenH,
            activePackage = activePkg,
            activeWindowTitle = activeTitle,
            windowCount = scannedWindows,
            rootNodes = rootNodes,
            indexedElements = indexedElements,
            screenshotBase64Jpeg = screenshotBase64,
            screenshotError = screenshotErr
        )
    }

    private fun getWindowRootCompat(win: AccessibilityWindowInfo): AccessibilityNodeInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                win.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                win.root
            }
        } catch (e: Exception) {
            try {
                win.root
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getChildCompat(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                node.getChild(index)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun dumpNodeRecursive(
        node: AccessibilityNodeInfo?,
        filterInvisible: Boolean,
        clipBounds: Rect,
        screenW: Int,
        screenH: Int,
        windowLayer: Int,
        windowType: String,
        windowTitle: String,
        indexCounter: AtomicInteger,
        indexedOut: MutableList<IndexedUiElement>
    ): A11yNode? {
        if (node == null) return null
        if (filterInvisible && !node.isVisibleToUser) return null

        val rawBounds = Rect()
        node.getBoundsInScreen(rawBounds)

        val visibleBounds = Rect(rawBounds)
        if (!visibleBounds.intersect(clipBounds)) {
            if (filterInvisible) return null
            visibleBounds.setEmpty()
        }

        val childClip = Rect(clipBounds)
        if (node.isScrollable) {
            childClip.intersect(rawBounds)
        }

        val actions = node.actionList?.mapNotNull { action ->
            val label = action.label?.toString()
            if (!label.isNullOrBlank()) "${action.id}:$label" else action.id.toString()
        } ?: emptyList()

        val candidate = A11yNode(
            index = -1,
            className = node.className?.toString().orEmpty(),
            packageName = node.packageName?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDesc = node.contentDescription?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            boundsInScreen = visibleBounds,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isFocusable = node.isFocusable,
            isFocused = node.isFocused,
            isEnabled = node.isEnabled,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isPassword = node.isPassword,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isSelected = node.isSelected,
            isVisibleToUser = node.isVisibleToUser,
            drawingOrder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) node.drawingOrder else 0,
            windowId = node.windowId,
            windowLayer = windowLayer,
            windowType = windowType,
            windowTitle = windowTitle,
            isImportantForAccessibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                node.isImportantForAccessibility
            } else true,
            actionList = actions
        )

        val assignedIndex = if (candidate.isInteractiveOrInformative()) {
            val idx = indexCounter.getAndIncrement()
            val (normX, normY) = CoordinateNormalizer.pixelToNormalized(
                candidate.centerX,
                candidate.centerY,
                screenW,
                screenH
            )
            indexedOut.add(
                IndexedUiElement(
                    index = idx,
                    shortClass = candidate.shortClassName,
                    className = candidate.className,
                    packageName = candidate.packageName,
                    text = candidate.text,
                    contentDesc = candidate.contentDesc,
                    resourceId = candidate.resourceId,
                    left = visibleBounds.left,
                    top = visibleBounds.top,
                    right = visibleBounds.right,
                    bottom = visibleBounds.bottom,
                    centerX = candidate.centerX,
                    centerY = candidate.centerY,
                    normX = normX,
                    normY = normY,
                    clickable = candidate.isClickable,
                    longClickable = candidate.isLongClickable,
                    scrollable = candidate.isScrollable,
                    editable = candidate.isEditable,
                    checkable = candidate.isCheckable,
                    checked = candidate.isChecked,
                    focused = candidate.isFocused,
                    enabled = candidate.isEnabled,
                    windowType = windowType,
                    windowLayer = windowLayer
                )
            )
            idx
        } else {
            -1
        }

        val finalNode = candidate.copy(index = assignedIndex)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val childInfo = getChildCompat(node, i) ?: continue
            val dumpedChild = dumpNodeRecursive(
                node = childInfo,
                filterInvisible = filterInvisible,
                clipBounds = childClip,
                screenW = screenW,
                screenH = screenH,
                windowLayer = windowLayer,
                windowType = windowType,
                windowTitle = windowTitle,
                indexCounter = indexCounter,
                indexedOut = indexedOut
            )
            if (dumpedChild != null) {
                finalNode.children.add(dumpedChild)
            }
        }
        return finalNode
    }

    private fun captureScreenshotBase64(
        service: AccessibilityService,
        jpegQuality: Int
    ): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null to "Requires Android 11 (API 30+)"
        }
        val firstAttempt = takeScreenshotOnce(service, jpegQuality)
        if (firstAttempt.first != null) return firstAttempt

        if (firstAttempt.second?.contains("code=$ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT") == true) {
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return takeScreenshotOnce(service, jpegQuality)
        }
        return firstAttempt
    }

    private fun takeScreenshotOnce(
        service: AccessibilityService,
        jpegQuality: Int
    ): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null to "API < 30"
        }
        val latch = CountDownLatch(1)
        val base64Ref = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            hwBuffer.close()
                            if (hwBitmap != null) {
                                val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap.recycle()
                                if (softBitmap != null) {
                                    val scaled = scaleBitmapIfNeeded(softBitmap, maxDimension = 1280)
                                    val baos = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), baos)
                                    if (scaled !== softBitmap) scaled.recycle()
                                    softBitmap.recycle()
                                    base64Ref.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                                } else {
                                    errorRef.set("Failed to copy hardware bitmap")
                                }
                            } else {
                                errorRef.set("wrapHardwareBuffer returned null")
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Screenshot processing failed", t)
                            errorRef.set(t.message ?: "Screenshot processing exception")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errorRef.set("takeScreenshot failed code=$errorCode")
                        latch.countDown()
                    }
                }
            )
            latch.await(4, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            errorRef.set(t.message ?: "takeScreenshot exception")
        }
        return base64Ref.get() to errorRef.get()
    }

    private fun scaleBitmapIfNeeded(src: Bitmap, maxDimension: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDimension && h <= maxDimension) return src
        val ratio = min(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    fun dumpToXml(snapshot: AtomicSnapshot): String {
        val writer = StringWriter()
        try {
            val serializer = Xml.newSerializer()
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.startTag("", "hierarchy")
            serializer.attribute("", "rotation", "0")
            serializer.attribute("", "width", snapshot.screenWidth.toString())
            serializer.attribute("", "height", snapshot.screenHeight.toString())
            for (root in snapshot.rootNodes) {
                serializeNodeXml(root, serializer)
            }
            serializer.endTag("", "hierarchy")
            serializer.endDocument()
        } catch (e: Exception) {
            Log.e(TAG, "XML serialization failed", e)
        }
        return writer.toString()
    }

    private fun serializeNodeXml(node: A11yNode, serializer: org.xmlpull.v1.XmlSerializer) {
        serializer.startTag("", "node")
        serializer.attribute("", "index", node.index.coerceAtLeast(0).toString())
        serializer.attribute("", "text", node.text)
        serializer.attribute("", "resource-id", node.resourceId)
        serializer.attribute("", "class", node.className)
        serializer.attribute("", "package", node.packageName)
        serializer.attribute("", "content-desc", node.contentDesc)
        serializer.attribute("", "checkable", node.isCheckable.toString())
        serializer.attribute("", "checked", node.isChecked.toString())
        serializer.attribute("", "clickable", node.isClickable.toString())
        serializer.attribute("", "enabled", node.isEnabled.toString())
        serializer.attribute("", "focusable", node.isFocusable.toString())
        serializer.attribute("", "focused", node.isFocused.toString())
        serializer.attribute("", "scrollable", node.isScrollable.toString())
        serializer.attribute("", "long-clickable", node.isLongClickable.toString())
        serializer.attribute("", "password", node.isPassword.toString())
        serializer.attribute("", "selected", node.isSelected.toString())
        serializer.attribute("", "visible-to-user", node.isVisibleToUser.toString())
        val b = node.boundsInScreen
        serializer.attribute("", "bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]")
        for (child in node.children) {
            serializeNodeXml(child, serializer)
        }
        serializer.endTag("", "node")
    }

    private fun windowTypeToString(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
        else -> "WINDOW_$type"
    }
}
