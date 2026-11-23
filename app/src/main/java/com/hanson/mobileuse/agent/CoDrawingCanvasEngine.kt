package com.hanson.mobileuse.agent

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Author channel of a co-drawing element so Human and AI drawings remain cleanly separable,
 * toggleable, undoable, and visually distinguishable inside the 0%-saturation Paper & Carbon palette.
 */
enum class CoDrawAuthor {
    HUMAN,
    AI
}

/**
 * Drawing tool / primitive kind supported on both in-app whiteboard and out-of-app overlay canvas.
 */
enum class CoDrawToolKind(val labelKo: String) {
    FREEHAND_PEN("펜"),
    HIGHLIGHTER_BAND("형광펜"),
    ARROW("화살표"),
    CIRCLE_LASSO("원형 표시"),
    SPOTLIGHT_RING("AI 강조링"),
    HIGHLIGHT_BOX("영역 박스"),
    CALLOUT_BADGE("말풍선")
}

/**
 * Overlay interaction state machine for out-of-app `SYSTEM_ALERT_WINDOW` co-drawing.
 */
enum class CrossAppOverlayMode(val labelKo: String) {
    /** Overlay floats above other apps while 100% of taps pass through to the app underneath. */
    PASSTHROUGH_WATCH("터치 통과"),
    /** Overlay captures touch input so the user can circle/sketch directly on top of another app. */
    CO_DRAW_ACTIVE("함께 그리기")
}

/**
 * Resolution-independent normalized 2D point in `[0f, 1f] × [0f, 1f]`.
 */
data class NormPoint(
    val nx: Float,
    val ny: Float
) {
    fun clamped(): NormPoint = NormPoint(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
}

/**
 * Normalized bounding box in `[0f, 1f]` viewport space.
 */
data class NormBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun overlaps(other: NormBounds): Boolean {
        return left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top
    }
}

/**
 * Unified vector element on the Human + AI Co-Drawing Canvas.
 * All coordinates are normalized `0f..1f` so strokes match identically across in-app boards,
 * out-of-app overlays, and desktop SSE bridges.
 */
data class CoDrawElement(
    val id: String = "cd_${UUID.randomUUID().toString().take(8)}",
    val author: CoDrawAuthor,
    val kind: CoDrawToolKind,
    val points: List<NormPoint> = emptyList(),
    val strokeWidthDp: Float = 3.2f,
    val label: String = "",
    val stepBadge: String = "",
    val radiusNorm: Float = 0.09f,
    val targetElementIndex: Int? = null,
    val dashed: Boolean = (author == CoDrawAuthor.AI),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun computeBounds(): NormBounds? {
        if (points.isEmpty()) return null
        return when (kind) {
            CoDrawToolKind.SPOTLIGHT_RING -> {
                val c = points.first().clamped()
                val r = radiusNorm.coerceIn(0.02f, 0.45f)
                NormBounds(
                    left = (c.nx - r).coerceIn(0f, 1f),
                    top = (c.ny - r).coerceIn(0f, 1f),
                    right = (c.nx + r).coerceIn(0f, 1f),
                    bottom = (c.ny + r).coerceIn(0f, 1f)
                )
            }
            else -> {
                var minX = 1f
                var minY = 1f
                var maxX = 0f
                var maxY = 0f
                for (p in points) {
                    val cp = p.clamped()
                    minX = min(minX, cp.nx)
                    minY = min(minY, cp.ny)
                    maxX = max(maxX, cp.nx)
                    maxY = max(maxY, cp.ny)
                }
                NormBounds(minX, minY, maxX, maxY)
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", "codraw_command")
        put("id", id)
        put("author", author.name)
        put("kind", kind.name)
        put("strokeWidthDp", strokeWidthDp.toDouble())
        put("label", label)
        put("stepBadge", stepBadge)
        put("radius", radiusNorm.toDouble())
        put("dashed", dashed)
        if (targetElementIndex != null) put("targetElementIndex", targetElementIndex)
        put(
            "points",
            JSONArray().apply {
                points.forEach { pt ->
                    put(
                        JSONObject()
                            .put("nx", pt.nx.coerceIn(0f, 1f).toDouble())
                            .put("ny", pt.ny.coerceIn(0f, 1f).toDouble())
                    )
                }
            }
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): CoDrawElement? {
            val author = runCatching {
                CoDrawAuthor.valueOf(obj.optString("author", "AI").trim().uppercase())
            }.getOrDefault(CoDrawAuthor.AI)
            val kind = runCatching {
                CoDrawToolKind.valueOf(obj.optString("kind", "SPOTLIGHT_RING").trim().uppercase())
            }.getOrNull() ?: return null

            val pts = mutableListOf<NormPoint>()
            val ptsArr = obj.optJSONArray("points")
            if (ptsArr != null && ptsArr.length() > 0) {
                for (i in 0 until ptsArr.length()) {
                    val pObj = ptsArr.optJSONObject(i) ?: continue
                    val nx = pObj.optDouble("nx", Double.NaN)
                    val ny = pObj.optDouble("ny", Double.NaN)
                    if (!nx.isNaN() && !ny.isNaN()) {
                        pts.add(NormPoint(nx.toFloat(), ny.toFloat()).clamped())
                    }
                }
            } else if (obj.has("nx") && obj.has("ny")) {
                val nx = obj.optDouble("nx", 0.5).toFloat()
                val ny = obj.optDouble("ny", 0.5).toFloat()
                pts.add(NormPoint(nx, ny).clamped())
                if (obj.has("nx2") && obj.has("ny2")) {
                    pts.add(
                        NormPoint(
                            obj.optDouble("nx2", 0.5).toFloat(),
                            obj.optDouble("ny2", 0.5).toFloat()
                        ).clamped()
                    )
                }
            }
            if (pts.isEmpty()) return null

            val targetIdx = if (obj.has("targetElementIndex") && !obj.isNull("targetElementIndex")) {
                obj.optInt("targetElementIndex")
            } else {
                null
            }

            return CoDrawElement(
                id = obj.optString("id", "").ifBlank { "cd_${UUID.randomUUID().toString().take(8)}" },
                author = author,
                kind = kind,
                points = pts,
                strokeWidthDp = obj.optDouble("strokeWidthDp", 3.2).toFloat().coerceIn(1.2f, 24f),
                label = obj.optString("label", "").trim().take(80),
                stepBadge = obj.optString("stepBadge", "").trim().take(12),
                radiusNorm = obj.optDouble("radius", 0.09).toFloat().coerceIn(0.02f, 0.45f),
                targetElementIndex = targetIdx,
                dashed = obj.optBoolean("dashed", author == CoDrawAuthor.AI)
            )
        }
    }
}

/**
 * Snapshot state exposed to both in-app and out-of-app Jetpack Compose co-drawing canvases.
 */
data class CoDrawingCanvasState(
    val elements: List<CoDrawElement> = emptyList(),
    val activeDraft: CoDrawElement? = null,
    val selectedTool: CoDrawToolKind = CoDrawToolKind.FREEHAND_PEN,
    val showHumanLayer: Boolean = true,
    val showAiLayer: Boolean = true,
    val overlayMode: CrossAppOverlayMode = CrossAppOverlayMode.PASSTHROUGH_WATCH,
    val showFloatingDynamicCard: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val revision: Long = 0L
) {
    val visibleElements: List<CoDrawElement>
        get() = elements.filter { elem ->
            (elem.author == CoDrawAuthor.HUMAN && showHumanLayer) ||
                (elem.author == CoDrawAuthor.AI && showAiLayer)
        }

    val humanElementCount: Int get() = elements.count { it.author == CoDrawAuthor.HUMAN }
    val aiElementCount: Int get() = elements.count { it.author == CoDrawAuthor.AI }
}

/**
 * Process-wide Human + AI Co-Drawing state engine backing both:
 * 1. In-App `CommandCenterScreen` interactive whiteboard (`CoDrawingStudioModalSheet`)
 * 2. Out-of-App `DynamicCanvasOverlayService` cross-app overlay (`SYSTEM_ALERT_WINDOW`)
 *
 * Thread-safe, pure-JVM testable (zero Android framework dependency required for core state & DSL).
 */
object CoDrawingCanvasEngine {

    private const val MAX_ELEMENTS = 160
    private const val MAX_UNDO_DEPTH = 48

    private val lock = Any()
    private val undoStack = ArrayDeque<List<CoDrawElement>>()
    private val redoStack = ArrayDeque<List<CoDrawElement>>()

    private val _state = MutableStateFlow(CoDrawingCanvasState())
    val state: StateFlow<CoDrawingCanvasState> = _state.asStateFlow()

    fun selectTool(tool: CoDrawToolKind) {
        _state.update { it.copy(selectedTool = tool) }
    }

    fun setOverlayMode(mode: CrossAppOverlayMode) {
        _state.update { it.copy(overlayMode = mode) }
    }

    fun toggleOverlayMode(): CrossAppOverlayMode {
        var next = CrossAppOverlayMode.PASSTHROUGH_WATCH
        _state.update { prev ->
            next = if (prev.overlayMode == CrossAppOverlayMode.PASSTHROUGH_WATCH) {
                CrossAppOverlayMode.CO_DRAW_ACTIVE
            } else {
                CrossAppOverlayMode.PASSTHROUGH_WATCH
            }
            prev.copy(overlayMode = next)
        }
        return next
    }

    fun setFloatingDynamicCardVisible(visible: Boolean) {
        _state.update { it.copy(showFloatingDynamicCard = visible) }
    }

    fun toggleLayerVisibility(author: CoDrawAuthor) {
        _state.update { prev ->
            when (author) {
                CoDrawAuthor.HUMAN -> prev.copy(showHumanLayer = !prev.showHumanLayer)
                CoDrawAuthor.AI -> prev.copy(showAiLayer = !prev.showAiLayer)
            }
        }
    }

    /** Begins an interactive human gesture stroke at normalized `(nx, ny)`. */
    fun beginHumanStroke(nx: Float, ny: Float, toolOverride: CoDrawToolKind? = null) {
        val start = NormPoint(nx, ny).clamped()
        _state.update { prev ->
            val tool = toolOverride ?: prev.selectedTool
            val widthDp = when (tool) {
                CoDrawToolKind.HIGHLIGHTER_BAND -> 16f
                CoDrawToolKind.CIRCLE_LASSO -> 3.4f
                CoDrawToolKind.ARROW -> 3.6f
                else -> 3.2f
            }
            prev.copy(
                activeDraft = CoDrawElement(
                    author = CoDrawAuthor.HUMAN,
                    kind = tool,
                    points = listOf(start),
                    strokeWidthDp = widthDp,
                    dashed = false
                )
            )
        }
    }

    /** Appends a normalized drag point to the active human stroke with micro-jitter filtering. */
    fun appendHumanStrokePoint(nx: Float, ny: Float) {
        val pt = NormPoint(nx, ny).clamped()
        _state.update { prev ->
            val draft = prev.activeDraft ?: return@update prev
            val last = draft.points.lastOrNull()
            if (last != null && hypot((pt.nx - last.nx).toDouble(), (pt.ny - last.ny).toDouble()) < 0.0035) {
                return@update prev
            }
            val updatedPoints = when (draft.kind) {
                CoDrawToolKind.ARROW, CoDrawToolKind.HIGHLIGHT_BOX -> listOf(draft.points.first(), pt)
                else -> (draft.points + pt).takeLast(320)
            }
            prev.copy(activeDraft = draft.copy(points = updatedPoints))
        }
    }

    /** Commits the in-progress human stroke into the undoable vector stack. */
    fun finishHumanStroke(): CoDrawElement? {
        synchronized(lock) {
            val prev = _state.value
            val draft = prev.activeDraft ?: return null
            if (draft.points.isEmpty()) {
                _state.value = prev.copy(activeDraft = null)
                return null
            }
            val finalized = if (draft.points.size == 1 && draft.kind == CoDrawToolKind.FREEHAND_PEN) {
                val p = draft.points.first()
                draft.copy(points = listOf(p, NormPoint((p.nx + 0.004f).coerceAtMost(1f), p.ny)))
            } else {
                draft
            }
            pushUndoLocked(prev.elements)
            val nextList = (prev.elements + finalized).takeLast(MAX_ELEMENTS)
            _state.value = prev.copy(
                elements = nextList,
                activeDraft = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                revision = prev.revision + 1
            )
            return finalized
        }
    }

    /** Adds an AI-generated (or remote SSE-streamed) vector annotation onto the shared canvas. */
    fun addAiElement(element: CoDrawElement): CoDrawElement {
        val normalized = element.copy(
            author = CoDrawAuthor.AI,
            points = element.points.map { it.clamped() }
        )
        synchronized(lock) {
            val prev = _state.value
            pushUndoLocked(prev.elements)
            val nextList = (prev.elements.filterNot { it.id == normalized.id } + normalized).takeLast(MAX_ELEMENTS)
            _state.value = prev.copy(
                elements = nextList,
                showAiLayer = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                revision = prev.revision + 1
            )
            return normalized
        }
    }

    /**
     * Creates and adds an AI spotlight ring or highlight box anchored precisely to an Android
     * accessibility node's screen bounds (`Rect`), enabling AI to point directly at a live app button.
     */
    fun addAiAnchorForNodeBounds(
        elementIndex: Int,
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        label: String,
        stepBadge: String = "",
        useBox: Boolean = false
    ): CoDrawElement? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val left = (bounds.left.toFloat() / screenWidth).coerceIn(0f, 1f)
        val top = (bounds.top.toFloat() / screenHeight).coerceIn(0f, 1f)
        val right = (bounds.right.toFloat() / screenWidth).coerceIn(0f, 1f)
        val bottom = (bounds.bottom.toFloat() / screenHeight).coerceIn(0f, 1f)
        val cx = ((left + right) / 2f).coerceIn(0f, 1f)
        val cy = ((top + bottom) / 2f).coerceIn(0f, 1f)
        val radius = (max(right - left, bottom - top) * 0.62f).coerceIn(0.045f, 0.28f)

        val elem = if (useBox) {
            CoDrawElement(
                author = CoDrawAuthor.AI,
                kind = CoDrawToolKind.HIGHLIGHT_BOX,
                points = listOf(NormPoint(left, top), NormPoint(right, bottom)),
                label = label.trim().take(64),
                stepBadge = stepBadge.trim().take(12),
                targetElementIndex = elementIndex,
                dashed = true
            )
        } else {
            CoDrawElement(
                author = CoDrawAuthor.AI,
                kind = CoDrawToolKind.SPOTLIGHT_RING,
                points = listOf(NormPoint(cx, cy)),
                radiusNorm = radius,
                label = label.trim().take(64),
                stepBadge = stepBadge.trim().take(12),
                targetElementIndex = elementIndex,
                dashed = true
            )
        }
        return addAiElement(elem)
    }

    fun undo(): Boolean {
        synchronized(lock) {
            if (undoStack.isEmpty()) return false
            val prev = _state.value
            val restored = undoStack.removeLast()
            redoStack.addLast(prev.elements)
            _state.value = prev.copy(
                elements = restored,
                activeDraft = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                revision = prev.revision + 1
            )
            return true
        }
    }

    fun redo(): Boolean {
        synchronized(lock) {
            if (redoStack.isEmpty()) return false
            val prev = _state.value
            val restored = redoStack.removeLast()
            undoStack.addLast(prev.elements)
            _state.value = prev.copy(
                elements = restored,
                activeDraft = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                revision = prev.revision + 1
            )
            return true
        }
    }

    /** Clears all elements, or only elements belonging to [authorFilter]. */
    fun clear(authorFilter: CoDrawAuthor? = null) {
        synchronized(lock) {
            val prev = _state.value
            val next = if (authorFilter == null) {
                emptyList()
            } else {
                prev.elements.filterNot { it.author == authorFilter }
            }
            if (next.size == prev.elements.size && prev.activeDraft == null) return
            pushUndoLocked(prev.elements)
            _state.value = prev.copy(
                elements = next,
                activeDraft = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                revision = prev.revision + 1
            )
        }
    }

    /** Full reset for tests or clean session switch. */
    fun resetForNewSession() {
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            _state.value = CoDrawingCanvasState()
        }
    }

    private fun pushUndoLocked(snapshot: List<CoDrawElement>) {
        if (undoStack.size >= MAX_UNDO_DEPTH) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
    }

    // =========================================================================
    // Inline LLM `CODRAW:` Directive Parser (works in live SSE streaming!)
    // =========================================================================

    private val CODRAW_RING_REGEX = Regex(
        """^\s*CODRAW\s*[:：]\s*RING\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)(?:\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )
    private val CODRAW_ARROW_REGEX = Regex(
        """^\s*CODRAW\s*[:：]\s*ARROW\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)(?:\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )
    private val CODRAW_BOX_REGEX = Regex(
        """^\s*CODRAW\s*[:：]\s*BOX\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)(?:\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )
    private val CODRAW_LABEL_REGEX = Regex(
        """^\s*CODRAW\s*[:：]\s*LABEL\s+([0-9.]+)\s+([0-9.]+)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private fun normalizeCoord(v: Float): Float = if (v > 1.0f && v <= 1000f) (v / 1000f).coerceIn(0f, 1f) else v.coerceIn(0f, 1f)

    /**
     * Parses a single `CODRAW:` directive line emitted by an LLM or SSE stream into a typed [CoDrawElement].
     * Supports both `0.0..1.0` normalized floats and `0..1000` Set-of-Marks integer coordinates!
     */
    fun parseCoDrawDirectiveLine(line: String, stepNumber: Int = 0): CoDrawElement? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("CODRAW", ignoreCase = true)) return null

        val badge = if (stepNumber > 0) stepNumber.toString() else ""

        CODRAW_RING_REGEX.matchEntire(trimmed)?.let { m ->
            val nx = normalizeCoord(m.groupValues[1].toFloatOrNull() ?: return null)
            val ny = normalizeCoord(m.groupValues[2].toFloatOrNull() ?: return null)
            val rawR = m.groupValues[3].toFloatOrNull() ?: 0.09f
            val r = (if (rawR > 1.0f && rawR <= 1000f) rawR / 1000f else rawR).coerceIn(0.03f, 0.42f)
            val label = m.groupValues[4].trim().take(64)
            return CoDrawElement(
                author = CoDrawAuthor.AI,
                kind = CoDrawToolKind.SPOTLIGHT_RING,
                points = listOf(NormPoint(nx, ny)),
                radiusNorm = r,
                label = label,
                stepBadge = badge,
                dashed = true
            )
        }

        CODRAW_ARROW_REGEX.matchEntire(trimmed)?.let { m ->
            val x1 = normalizeCoord(m.groupValues[1].toFloatOrNull() ?: return null)
            val y1 = normalizeCoord(m.groupValues[2].toFloatOrNull() ?: return null)
            val x2 = normalizeCoord(m.groupValues[3].toFloatOrNull() ?: return null)
            val y2 = normalizeCoord(m.groupValues[4].toFloatOrNull() ?: return null)
            val label = m.groupValues[5].trim().take(64)
            return CoDrawElement(
                author = CoDrawAuthor.AI,
                kind = CoDrawToolKind.ARROW,
                points = listOf(NormPoint(x1, y1), NormPoint(x2, y2)),
                strokeWidthDp = 3.4f,
                label = label,
                stepBadge = badge,
                dashed = false
            )
        }

        CODRAW_BOX_REGEX.matchEntire(trimmed)?.let { m ->
            val l = normalizeCoord(m.groupValues[1].toFloatOrNull() ?: return null)
            val t = normalizeCoord(m.groupValues[2].toFloatOrNull() ?: return null)
            val r = normalizeCoord(m.groupValues[3].toFloatOrNull() ?: return null)
            val b = normalizeCoord(m.groupValues[4].toFloatOrNull() ?: return null)
            val label = m.groupValues[5].trim().take(64)
            return CoDrawElement(
                author = CoDrawAuthor.AI,
                kind = CoDrawToolKind.HIGHLIGHT_BOX,
                points = listOf(NormPoint(min(l, r), min(t, b)), NormPoint(max(l, r), max(t, b))),
                strokeWidthDp = 2.6f,
                label = label,
                stepBadge = badge,
                dashed = true
            )
        }

        CODRAW_LABEL_REGEX.matchEntire(trimmed)?.let { m ->
            val nx = normalizeCoord(m.groupValues[1].toFloatOrNull() ?: return null)
            val ny = normalizeCoord(m.groupValues[2].toFloatOrNull() ?: return null)
            val label = m.groupValues[3].trim().take(64)
            return CoDrawElement(
                author = CoDrawAuthor.AI,
                kind = CoDrawToolKind.CALLOUT_BADGE,
                points = listOf(NormPoint(nx, ny)),
                label = label,
                stepBadge = badge,
                dashed = false
            )
        }

        return null
    }

    /**
     * Converts current human sketches into structured natural-language spatial coordinates (0..1000 & 0..1)
     * so VLMs/LLMs understand exactly where on the mobile UI the user circled, pointed, or highlighted.
     */
    fun buildSpatialPromptSummary(
        elements: List<CoDrawElement> = _state.value.elements,
        foregroundAppLabel: String = ""
    ): String {
        val humanStrokes = elements.filter { it.author == CoDrawAuthor.HUMAN }
        if (humanStrokes.isEmpty()) return ""
        return buildString {
            appendLine("[사용자 화면 공동 드로잉 표시]")
            if (foregroundAppLabel.isNotBlank()) {
                appendLine("- 대상 화면: $foregroundAppLabel")
            }
            humanStrokes.takeLast(8).forEachIndexed { idx, el ->
                val b = el.computeBounds() ?: return@forEachIndexed
                val x1 = (b.left * 1000).toInt()
                val y1 = (b.top * 1000).toInt()
                val x2 = (b.right * 1000).toInt()
                val y2 = (b.bottom * 1000).toInt()
                val cx = (b.centerX * 1000).toInt()
                val cy = (b.centerY * 1000).toInt()
                appendLine(
                    "- 표시 #${idx + 1} (${el.kind.labelKo}): 정규화 좌표 x=${x1}..${x2}, y=${y1}..${y2} (중심 ${cx},${cy})"
                )
            }
            append("필요하면 응답에 `CODRAW: RING <x> <y> <r> <라벨>` 또는 `CODRAW: ARROW <x1> <y1> <x2> <y2> <라벨>` 줄을 포함해 화면에 함께 표시하세요.")
        }.trim()
    }
}
