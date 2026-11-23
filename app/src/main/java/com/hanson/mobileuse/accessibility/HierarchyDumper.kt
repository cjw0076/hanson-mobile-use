package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Xml
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

typealias AtomicUiSnapshot = AtomicSnapshot

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
    val somScreenshotBase64Jpeg: String? = null,
    val screenshotError: String? = null,
    val isSecureWindow: Boolean = false
) {
    /**
     * Preferred screenshot for VLM visual grounding: Set-of-Marks annotated JPEG when available,
     * falling back to the raw screenshot JPEG.
     */
    val visualGroundingBase64: String?
        get() = somScreenshotBase64Jpeg ?: screenshotBase64Jpeg

    fun toPromptContext(maxElements: Int = 100): String {
        val sb = StringBuilder()
        sb.appendLine("Screen Size: ${screenWidth}x${screenHeight} px (Normalized Grid: 0..1000 x 0..1000)")
        sb.appendLine("Active Package: ${activePackage.ifBlank { "unknown" }}")
        if (activeWindowTitle.isNotBlank()) {
            sb.appendLine("Active Window Title: $activeWindowTitle")
        }
        if (isSecureWindow) {
            sb.appendLine("Security Flag: FLAG_SECURE window detected (OS blocked screenshot capture)")
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
        if (somScreenshotBase64Jpeg != null) {
            put("somScreenshot", somScreenshotBase64Jpeg)
        }
        if (screenshotError != null) {
            put("screenshotError", screenshotError)
        }
        if (isSecureWindow) {
            put("isSecureWindow", true)
        }
    }
}

/**
 * Ported & enhanced from Google Artemis `HierarchyDumper.java`.
 * Performs strict multi-window Z-layer UI tree extraction with Region-based top-down occlusion,
 * recursion/node caps, API 33+ hybrid descendant prefetching, and deadlock-free hardware
 * screenshot capture (preferring API 34+ `takeScreenshotOfWindow` to exclude HUD & SystemUI).
 */
object HierarchyDumper {
    private const val TAG = "HierarchyDumper"
    private const val ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3
    private const val ERROR_TAKE_SCREENSHOT_INVALID_WINDOW = 5
    private const val ERROR_TAKE_SCREENSHOT_SECURE_WINDOW = 6

    const val MAX_RECURSION_DEPTH = 40
    const val MAX_COLLECTED_NODES = 600
    const val FLOATING_HUD_WINDOW_TITLE = "HansonFloatingHud"

    private val PERMISSION_DIALOG_PACKAGES = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hanson-Screenshot-Worker").apply { isDaemon = true }
    }

    val perceptionMutex = Mutex()

    private data class WindowCandidate(
        val window: AccessibilityWindowInfo,
        val root: AccessibilityNodeInfo,
        val packageName: String,
        val title: String,
        val bounds: Rect,
        val isActive: Boolean,
        val isFocused: Boolean,
        val layer: Int,
        val windowId: Int
    )

    private data class ScreenshotCaptureResult(
        val rawBase64: String? = null,
        val somBase64: String? = null,
        val error: String? = null,
        val errorCode: Int = 0,
        val isSecureWindow: Boolean = false
    )

    fun isSystemChromeOrEdgePackage(pkg: String, windowTitle: String = ""): Boolean {
        val p = pkg.trim().lowercase()
        val t = windowTitle.trim().lowercase()
        if (p == "com.android.systemui") return true
        if (p.contains("cocktailbar") || p.contains("appsedge") || p.contains("edgelighting")) return true
        if (t.contains("edge 패널") || t.contains("edge panel") || t.contains("edge_panel")) return true
        return false
    }

    fun isExplicitPermissionOrInstallerDialog(pkg: String): Boolean {
        val p = pkg.trim().lowercase()
        return p in PERMISSION_DIALOG_PACKAGES ||
            p.contains("permissioncontroller") ||
            p.contains("packageinstaller")
    }

    fun isHansonOwnPackage(pkg: String, servicePackageName: String = ""): Boolean {
        val p = pkg.trim()
        if (p.isEmpty()) return false
        return p == "com.hanson.mobileuse" ||
            p == "com.hanson.mobileuse.debug" ||
            (servicePackageName.isNotBlank() && p == servicePackageName)
    }

    fun isFloatingHudTitle(windowTitle: String): Boolean {
        val t = windowTitle.trim()
        if (t.isEmpty()) return false
        return t.equals(FLOATING_HUD_WINDOW_TITLE, ignoreCase = true) ||
            t.contains("HansonFloatingHud", ignoreCase = true)
    }

    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val currentBounds = wm.currentWindowMetrics.bounds
                if (currentBounds.width() > 0 && currentBounds.height() > 0) {
                    return currentBounds.width() to currentBounds.height()
                }
                val maxBounds = wm.maximumWindowMetrics.bounds
                if (maxBounds.width() > 0 && maxBounds.height() > 0) {
                    return maxBounds.width() to maxBounds.height()
                }
            }
            val res = context.resources?.displayMetrics
            if (res != null && res.widthPixels > 0 && res.heightPixels > 0) {
                res.widthPixels to res.heightPixels
            } else {
                1080 to 2400
            }
        } catch (_: Throwable) {
            1080 to 2400
        }
    }

    fun captureAtomicSnapshot(
        service: AccessibilityService,
        includeScreenshot: Boolean = true,
        filterInvisible: Boolean = true,
        jpegQuality: Int = 78
    ): AtomicSnapshot = runBlocking {
        perceptionMutex.withLock {
            captureAtomicSnapshotInternal(service, includeScreenshot, filterInvisible, jpegQuality)
        }
    }

    suspend fun captureAtomicSnapshotSuspend(
        service: AccessibilityService,
        includeScreenshot: Boolean = true,
        filterInvisible: Boolean = true,
        jpegQuality: Int = 78
    ): AtomicSnapshot = perceptionMutex.withLock {
        captureAtomicSnapshotInternal(service, includeScreenshot, filterInvisible, jpegQuality)
    }

    private fun captureAtomicSnapshotInternal(
        service: AccessibilityService,
        includeScreenshot: Boolean = true,
        filterInvisible: Boolean = true,
        jpegQuality: Int = 78
    ): AtomicSnapshot {
        val (screenW, screenH) = getScreenDimensions(service)
        val screenBounds = Rect(0, 0, screenW, screenH)

        val windows = try {
            service.windows
        } catch (_: Throwable) {
            null
        }

        val rootNodes = mutableListOf<A11yNode>()
        val indexedElements = mutableListOf<IndexedUiElement>()
        val indexCounter = AtomicInteger(0)
        val collectedNodeCounter = AtomicInteger(0)
        var activePkg = ""
        var activeTitle = ""
        var scannedWindows = 0
        var primaryTargetWindowId = -1
        var primaryTargetWindowBounds: Rect? = null

        val externalCandidates = mutableListOf<WindowCandidate>()
        val hansonCandidates = mutableListOf<WindowCandidate>()

        if (!windows.isNullOrEmpty()) {
            for (win in windows) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && win.displayId != Display.DEFAULT_DISPLAY) {
                    continue
                }
                val type = win.type
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                    type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
                ) {
                    continue
                }

                val winTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    win.title?.toString().orEmpty()
                } else ""
                if (isFloatingHudTitle(winTitle)) continue

                val root = getWindowRootCompat(win) ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (isSystemChromeOrEdgePackage(pkg, winTitle)) continue

                val isAllowedAppWindow = type == AccessibilityWindowInfo.TYPE_APPLICATION
                val isAllowedPermissionDialog = type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    isExplicitPermissionOrInstallerDialog(pkg) &&
                    (win.isActive || win.isFocused)
                if (!isAllowedAppWindow && !isAllowedPermissionDialog) {
                    continue
                }

                val winBounds = Rect()
                win.getBoundsInScreen(winBounds)
                if (winBounds.width() <= 0 || winBounds.height() <= 0) continue

                val candidate = WindowCandidate(
                    window = win,
                    root = root,
                    packageName = pkg,
                    title = winTitle,
                    bounds = winBounds,
                    isActive = win.isActive,
                    isFocused = win.isFocused,
                    layer = win.layer,
                    windowId = win.id
                )

                if (isHansonOwnPackage(pkg, service.packageName)) {
                    // Only retain Hanson's own TYPE_APPLICATION window as fallback when no external target app is visible.
                    if (isAllowedAppWindow) {
                        hansonCandidates.add(candidate)
                    }
                } else {
                    externalCandidates.add(candidate)
                }
            }
        }

        // Exclude Hanson's own packages whenever dumping external target apps.
        val selectedWindows = (if (externalCandidates.isNotEmpty()) externalCandidates else hansonCandidates)
            .sortedWith(
                compareByDescending<WindowCandidate> { it.isActive || it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.layer }
            )

        if (selectedWindows.isNotEmpty()) {
            val occludedRegion = createRegionSafe()
            for (cand in selectedWindows) {
                if (collectedNodeCounter.get() >= MAX_COLLECTED_NODES) break

                val windowClip = Rect(screenBounds)
                if (!cand.bounds.isEmpty && !windowClip.intersect(cand.bounds)) {
                    continue
                }
                if (occludedRegion != null && isRectFullyOccluded(occludedRegion, windowClip)) {
                    continue
                }

                scannedWindows++
                val winType = windowTypeToString(cand.window.type)
                if (activePkg.isBlank() && cand.packageName.isNotBlank()) {
                    activePkg = cand.packageName
                    activeTitle = cand.title
                }
                if (primaryTargetWindowId <= 0 &&
                    cand.window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    cand.windowId > 0
                ) {
                    primaryTargetWindowId = cand.windowId
                    primaryTargetWindowBounds = Rect(windowClip)
                }

                val dumpedRoot = dumpNodeRecursive(
                    node = cand.root,
                    filterInvisible = filterInvisible,
                    clipBounds = windowClip,
                    screenW = screenW,
                    screenH = screenH,
                    windowLayer = cand.layer,
                    windowType = winType,
                    windowTitle = cand.title,
                    indexCounter = indexCounter,
                    collectedNodeCounter = collectedNodeCounter,
                    depth = 0,
                    occludedRegion = occludedRegion,
                    indexedOut = indexedElements
                )
                if (dumpedRoot != null) {
                    rootNodes.add(dumpedRoot)
                }

                if (occludedRegion != null) {
                    unionRectSafe(occludedRegion, windowClip)
                }
            }
        } else {
            // Fallback cleanly to rootInActiveWindow when filtered windows yield 0 app windows
            val activeRoot = try {
                service.rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            if (activeRoot != null) {
                val rootPkg = activeRoot.packageName?.toString().orEmpty()
                if (!isSystemChromeOrEdgePackage(rootPkg)) {
                    scannedWindows = 1
                    activePkg = rootPkg
                    if (activeRoot.windowId > 0) {
                        primaryTargetWindowId = activeRoot.windowId
                    }
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
                        collectedNodeCounter = collectedNodeCounter,
                        depth = 0,
                        occludedRegion = null,
                        indexedOut = indexedElements
                    )
                    if (dumpedRoot != null) {
                        rootNodes.add(dumpedRoot)
                    }
                }
            }
        }

        var screenshotBase64: String? = null
        var somScreenshotBase64: String? = null
        var screenshotErr: String? = null
        var isSecureWindow = false
        if (includeScreenshot) {
            val capture = captureScreenshotBase64(
                service = service,
                jpegQuality = jpegQuality,
                elements = indexedElements,
                screenWidth = screenW,
                screenHeight = screenH,
                targetWindowId = primaryTargetWindowId,
                targetWindowBounds = primaryTargetWindowBounds
            )
            screenshotBase64 = capture.rawBase64
            somScreenshotBase64 = capture.somBase64
            screenshotErr = capture.error
            isSecureWindow = capture.isSecureWindow
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
            somScreenshotBase64Jpeg = somScreenshotBase64,
            screenshotError = screenshotErr,
            isSecureWindow = isSecureWindow
        )
    }

    private fun createRegionSafe(): Region? = try {
        Region()
    } catch (_: Throwable) {
        null
    }

    private fun unionRectSafe(region: Region, rect: Rect) {
        try {
            if (!rect.isEmpty) {
                region.op(rect, Region.Op.UNION)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isRectFullyOccluded(occludedRegion: Region, rect: Rect): Boolean {
        if (rect.isEmpty) return true
        return try {
            if (occludedRegion.isEmpty) return false
            val remaining = Region(rect)
            remaining.op(occludedRegion, Region.Op.DIFFERENCE)
            remaining.isEmpty
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPointOccluded(occludedRegion: Region, x: Int, y: Int): Boolean {
        return try {
            !occludedRegion.isEmpty && occludedRegion.contains(x, y)
        } catch (_: Throwable) {
            false
        }
    }

    private fun getWindowRootCompat(win: AccessibilityWindowInfo): AccessibilityNodeInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                win.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                win.root
            }
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        collectedNodeCounter: AtomicInteger,
        depth: Int,
        occludedRegion: Region?,
        indexedOut: MutableList<IndexedUiElement>
    ): A11yNode? {
        if (node == null) return null
        if (depth > MAX_RECURSION_DEPTH) return null
        if (collectedNodeCounter.get() >= MAX_COLLECTED_NODES) return null
        if (filterInvisible && !node.isVisibleToUser) return null

        val rawBounds = Rect()
        node.getBoundsInScreen(rawBounds)

        val visibleBounds = Rect(rawBounds)
        if (!visibleBounds.intersect(clipBounds)) {
            if (filterInvisible) return null
            visibleBounds.setEmpty()
        }

        if (filterInvisible && occludedRegion != null && isRectFullyOccluded(occludedRegion, visibleBounds)) {
            return null
        }

        if (collectedNodeCounter.incrementAndGet() > MAX_COLLECTED_NODES) {
            return null
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

        val centerOccluded = occludedRegion != null &&
            isPointOccluded(occludedRegion, candidate.centerX, candidate.centerY)

        val assignedIndex = if (!centerOccluded && candidate.isInteractiveOrInformative()) {
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
        if (depth < MAX_RECURSION_DEPTH) {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (collectedNodeCounter.get() >= MAX_COLLECTED_NODES) break
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
                    collectedNodeCounter = collectedNodeCounter,
                    depth = depth + 1,
                    occludedRegion = occludedRegion,
                    indexedOut = indexedOut
                )
                if (dumpedChild != null) {
                    finalNode.children.add(dumpedChild)
                }
            }
        }
        return finalNode
    }

    private fun captureScreenshotBase64(
        service: AccessibilityService,
        jpegQuality: Int,
        elements: List<IndexedUiElement> = emptyList(),
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        targetWindowId: Int = -1,
        targetWindowBounds: Rect? = null
    ): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCaptureResult(error = "Requires Android 11 (API 30+)")
        }
        if (isOnMainThread()) {
            Log.w(TAG, "captureScreenshotBase64 invoked on main thread; skipping blocking screenshot capture to avoid deadlock")
            return ScreenshotCaptureResult(error = "Skipped screenshot capture on main thread")
        }

        val preferWindowCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && targetWindowId > 0
        val firstAttempt = takeScreenshotOnce(
            service = service,
            jpegQuality = jpegQuality,
            elements = elements,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            targetWindowId = if (preferWindowCapture) targetWindowId else -1,
            targetWindowBounds = if (preferWindowCapture) targetWindowBounds else null
        )
        if (firstAttempt.rawBase64 != null || firstAttempt.isSecureWindow) {
            return firstAttempt
        }

        if (firstAttempt.errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return firstAttempt
            }
            val retry = takeScreenshotOnce(
                service = service,
                jpegQuality = jpegQuality,
                elements = elements,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                targetWindowId = if (preferWindowCapture) targetWindowId else -1,
                targetWindowBounds = if (preferWindowCapture) targetWindowBounds else null
            )
            if (retry.rawBase64 != null || retry.isSecureWindow || !preferWindowCapture) {
                return retry
            }
        }

        // Fallback to full-display capture if window-scoped capture failed (e.g. window closed / INVALID_WINDOW)
        if (preferWindowCapture &&
            (firstAttempt.errorCode == ERROR_TAKE_SCREENSHOT_INVALID_WINDOW || firstAttempt.rawBase64 == null)
        ) {
            return takeScreenshotOnce(
                service = service,
                jpegQuality = jpegQuality,
                elements = elements,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                targetWindowId = -1,
                targetWindowBounds = null
            )
        }
        return firstAttempt
    }

    private fun isOnMainThread(): Boolean = try {
        Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
    } catch (_: Throwable) {
        false
    }

    private fun takeScreenshotOnce(
        service: AccessibilityService,
        jpegQuality: Int,
        elements: List<IndexedUiElement>,
        screenWidth: Int,
        screenHeight: Int,
        targetWindowId: Int,
        targetWindowBounds: Rect?
    ): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCaptureResult(error = "API < 30")
        }
        if (isOnMainThread()) {
            Log.w(TAG, "takeScreenshotOnce called on main thread; refusing blocking latch.await")
            return ScreenshotCaptureResult(error = "Skipped screenshot capture on main thread")
        }

        val latch = CountDownLatch(1)
        val base64Ref = AtomicReference<String?>(null)
        val somBase64Ref = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)
        val errorCodeRef = AtomicInteger(0)
        val secureWindowRef = AtomicBoolean(false)

        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val hwBuffer = screenshot.hardwareBuffer
                var hwBitmap: Bitmap? = null
                var softBitmap: Bitmap? = null
                var srgbBitmap: Bitmap? = null
                var scaledBitmap: Bitmap? = null
                var somBitmap: Bitmap? = null
                try {
                    val colorSpace = screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                    hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                    if (hwBitmap != null) {
                        softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        if (softBitmap != null) {
                            srgbBitmap = ensureSrgbArgb8888(softBitmap)
                            scaledBitmap = prepareScreenAlignedScaledBitmap(
                                src = srgbBitmap,
                                maxDimension = 960,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                windowBounds = if (targetWindowId > 0) targetWindowBounds else null
                            )
                            val quality = jpegQuality.coerceIn(45, 85)
                            val baos = ByteArrayOutputStream()
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                            base64Ref.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))

                            if (elements.isNotEmpty()) {
                                somBitmap = SetOfMarksAnnotator.annotateBitmap(
                                    source = scaledBitmap,
                                    elements = elements,
                                    screenWidth = screenWidth,
                                    screenHeight = screenHeight
                                )
                                if (somBitmap != null) {
                                    val somBaos = ByteArrayOutputStream()
                                    somBitmap.compress(Bitmap.CompressFormat.JPEG, quality, somBaos)
                                    somBase64Ref.set(Base64.encodeToString(somBaos.toByteArray(), Base64.NO_WRAP))
                                }
                            }
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
                    try {
                        hwBuffer.close()
                    } catch (_: Throwable) {
                    }
                    if (somBitmap != null && !somBitmap.isRecycled) somBitmap.recycle()
                    if (scaledBitmap != null &&
                        scaledBitmap !== srgbBitmap &&
                        scaledBitmap !== softBitmap &&
                        !scaledBitmap.isRecycled
                    ) {
                        scaledBitmap.recycle()
                    }
                    if (srgbBitmap != null && srgbBitmap !== softBitmap && !srgbBitmap.isRecycled) {
                        srgbBitmap.recycle()
                    }
                    if (softBitmap != null && !softBitmap.isRecycled) softBitmap.recycle()
                    if (hwBitmap != null && !hwBitmap.isRecycled) hwBitmap.recycle()
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                errorCodeRef.set(errorCode)
                if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    secureWindowRef.set(true)
                    Log.w(TAG, "Screenshot blocked by FLAG_SECURE window (code=$errorCode)")
                    errorRef.set("FLAG_SECURE window detected (code=$errorCode)")
                } else {
                    errorRef.set("takeScreenshot failed code=$errorCode")
                }
                latch.countDown()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && targetWindowId > 0) {
                service.takeScreenshotOfWindow(targetWindowId, screenshotExecutor, callback)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, callback)
            }
            val completed = latch.await(4, TimeUnit.SECONDS)
            if (!completed && base64Ref.get() == null && errorRef.get() == null) {
                errorRef.set("takeScreenshot timed out after 4s")
            }
        } catch (t: Throwable) {
            errorRef.set(t.message ?: "takeScreenshot exception")
        }

        return ScreenshotCaptureResult(
            rawBase64 = base64Ref.get(),
            somBase64 = somBase64Ref.get(),
            error = errorRef.get(),
            errorCode = errorCodeRef.get(),
            isSecureWindow = secureWindowRef.get()
        )
    }

    private fun ensureSrgbArgb8888(src: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        if (src.config == Bitmap.Config.ARGB_8888 && src.colorSpace == srgb) {
            return src
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888, true, srgb)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    /**
     * Scales the captured bitmap to fit within [maxDimension]. When a window-scoped capture
     * (`takeScreenshotOfWindow`) has bounds smaller than the full display, composites it at its
     * exact screen offset on an sRGB canvas so normalized 0..1000 & SoM coordinates stay 1:1 aligned.
     */
    private fun prepareScreenAlignedScaledBitmap(
        src: Bitmap,
        maxDimension: Int,
        screenWidth: Int,
        screenHeight: Int,
        windowBounds: Rect?
    ): Bitmap {
        if (windowBounds == null || screenWidth <= 0 || screenHeight <= 0) {
            return scaleBitmapIfNeeded(src, maxDimension)
        }
        val coversFullScreen = windowBounds.left <= 2 &&
            windowBounds.top <= 2 &&
            windowBounds.width() >= screenWidth - 4 &&
            windowBounds.height() >= screenHeight - 4
        if (coversFullScreen) {
            return scaleBitmapIfNeeded(src, maxDimension)
        }

        val scale = min(maxDimension.toFloat() / screenWidth, maxDimension.toFloat() / screenHeight)
            .coerceAtMost(1f)
        val outW = (screenWidth * scale).toInt().coerceAtLeast(1)
        val outH = (screenHeight * scale).toInt().coerceAtLeast(1)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val composed = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888, false, srgb)
        val canvas = Canvas(composed)
        canvas.drawColor(Color.BLACK)
        val dstRect = RectF(
            windowBounds.left * scale,
            windowBounds.top * scale,
            windowBounds.right * scale,
            windowBounds.bottom * scale
        )
        canvas.drawBitmap(src, null, dstRect, null)
        return composed
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
