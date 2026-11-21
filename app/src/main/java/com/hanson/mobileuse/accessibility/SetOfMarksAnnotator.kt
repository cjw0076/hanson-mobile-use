package com.hanson.mobileuse.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Computed Set-of-Marks (SoM) bounding box and numbered badge placement in bitmap pixel coordinates.
 */
data class SomBadgePlacement(
    val elementIndex: Int,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val badgeLeft: Float,
    val badgeTop: Float,
    val badgeRight: Float,
    val badgeBottom: Float,
    val roleTag: String // "EDITABLE", "CLICKABLE", "SCROLLABLE", "INFO"
)

/**
 * Set-of-Marks (SoM) Visual Grounding Annotator for Android UI Screenshots.
 *
 * Overlays numbered `[index]` badges and bounding boxes directly onto captured screenshots
 * so multimodal VLMs (Gemini, Claude, GPT, OpenRouter, Qwen-VL, local Ollama vision models)
 * and MCP clients can ground unlabeled icons, WebView controls, modal close (`X`) buttons,
 * and dense commercial app layouts with exact `element_index` precision.
 */
object SetOfMarksAnnotator {

    private const val DEFAULT_MAX_MARKS = 60

    /**
     * Pure geometric layout pass (100% JVM-testable without Android Canvas) that selects
     * interactive UI elements, scales screen bounds to target bitmap dimensions, clamps
     * badges inside viewport edges, and nudges overlapping badge pills apart.
     */
    fun computeBadgePlacements(
        elements: List<IndexedUiElement>,
        screenWidth: Int,
        screenHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        maxMarks: Int = DEFAULT_MAX_MARKS
    ): List<SomBadgePlacement> {
        if (elements.isEmpty() || screenWidth <= 0 || screenHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return emptyList()
        }

        val scaleX = bitmapWidth.toFloat() / screenWidth.toFloat()
        val scaleY = bitmapHeight.toFloat() / screenHeight.toFloat()
        val fullArea = bitmapWidth.toFloat() * bitmapHeight.toFloat()

        // Prioritize interactive controls (editable, clickable, checkable, longClickable),
        // skipping giant full-screen container frames (> 85% of screen area) unless scrollable.
        val prioritized = elements.filter { el ->
            val w = (el.right - el.left) * scaleX
            val h = (el.bottom - el.top) * scaleY
            val area = w * h
            val nonDegenerate = w >= 6f && h >= 6f
            val notFullScreenWrapper = area < fullArea * 0.85f
            nonDegenerate && (
                el.editable ||
                    (el.clickable && notFullScreenWrapper) ||
                    (el.checkable && notFullScreenWrapper) ||
                    (el.longClickable && notFullScreenWrapper)
                )
        }.sortedWith(
            compareByDescending<IndexedUiElement> { it.editable }
                .thenByDescending { it.clickable }
                .thenBy { it.index }
        ).take(maxMarks)
            .sortedBy { it.index }

        val placedBadges = mutableListOf<SomBadgePlacement>()
        val charW = (bitmapWidth * 0.016f).coerceIn(7f, 13f)
        val badgeH = (bitmapWidth * 0.028f).coerceIn(15f, 24f)
        val padX = 5f

        for (el in prioritized) {
            val bLeft = (el.left * scaleX).coerceIn(0f, bitmapWidth - 2f)
            val bTop = (el.top * scaleY).coerceIn(0f, bitmapHeight - 2f)
            val bRight = (el.right * scaleX).coerceIn(bLeft + 2f, bitmapWidth.toFloat())
            val bBottom = (el.bottom * scaleY).coerceIn(bTop + 2f, bitmapHeight.toFloat())

            val labelText = el.index.toString()
            val badgeW = max(badgeH, labelText.length * charW + padX * 2f)

            // Default anchor: top-left corner of element box, clamped inside bitmap bounds
            var pillLeft = bLeft.coerceIn(1f, max(1f, bitmapWidth - badgeW - 1f))
            var pillTop = (bTop - badgeH * 0.35f).coerceIn(1f, max(1f, bitmapHeight - badgeH - 1f))

            // Simple 2-step overlap nudge against already-placed badges
            repeat(2) {
                val colliding = placedBadges.firstOrNull { prev ->
                    pillLeft < prev.badgeRight + 2f &&
                        (pillLeft + badgeW) > prev.badgeLeft - 2f &&
                        pillTop < prev.badgeBottom + 2f &&
                        (pillTop + badgeH) > prev.badgeTop - 2f
                }
                if (colliding != null) {
                    val shiftedRight = colliding.badgeRight + 3f
                    if (shiftedRight + badgeW <= bitmapWidth - 1f && shiftedRight <= bRight) {
                        pillLeft = shiftedRight
                    } else {
                        pillTop = (colliding.badgeBottom + 2f).coerceIn(1f, max(1f, bitmapHeight - badgeH - 1f))
                    }
                }
            }

            val role = when {
                el.editable -> "EDITABLE"
                el.clickable || el.longClickable || el.checkable -> "CLICKABLE"
                el.scrollable -> "SCROLLABLE"
                else -> "INFO"
            }

            placedBadges.add(
                SomBadgePlacement(
                    elementIndex = el.index,
                    boxLeft = bLeft,
                    boxTop = bTop,
                    boxRight = bRight,
                    boxBottom = bBottom,
                    badgeLeft = pillLeft,
                    badgeTop = pillTop,
                    badgeRight = pillLeft + badgeW,
                    badgeBottom = pillTop + badgeH,
                    roleTag = role
                )
            )
        }

        return placedBadges
    }

    /**
     * Renders Set-of-Marks bounding boxes and `[index]` badges onto a mutable copy of [source].
     * Returns a new [Bitmap] (caller owns recycling) or `null` if rendering is unavailable.
     */
    fun annotateBitmap(
        source: Bitmap,
        elements: List<IndexedUiElement>,
        screenWidth: Int,
        screenHeight: Int,
        maxMarks: Int = DEFAULT_MAX_MARKS
    ): Bitmap? {
        return try {
            val placements = computeBadgePlacements(
                elements = elements,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                bitmapWidth = source.width,
                bitmapHeight = source.height,
                maxMarks = maxMarks
            )
            val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            if (placements.isEmpty()) return out

            val canvas = Canvas(out)
            val strokeWidth = (source.width * 0.0028f).coerceIn(1.8f, 3.2f)

            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = 1.2f
                color = Color.WHITE
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            for (mark in placements) {
                val accentColor = when (mark.roleTag) {
                    "EDITABLE" -> Color.rgb(10, 132, 255)   // High-visibility blue for text fields
                    "CLICKABLE" -> Color.rgb(235, 45, 58)   // High-visibility crimson for buttons/taps
                    else -> Color.rgb(36, 178, 96)          // Emerald for scrollable/other
                }

                boxPaint.color = accentColor
                canvas.drawRect(mark.boxLeft, mark.boxTop, mark.boxRight, mark.boxBottom, boxPaint)

                badgeBgPaint.color = accentColor
                val pillRect = RectF(mark.badgeLeft, mark.badgeTop, mark.badgeRight, mark.badgeBottom)
                val corner = (mark.badgeBottom - mark.badgeTop) * 0.28f
                canvas.drawRoundRect(pillRect, corner, corner, badgeBgPaint)
                canvas.drawRoundRect(pillRect, corner, corner, badgeBorderPaint)

                val badgeHeight = mark.badgeBottom - mark.badgeTop
                textPaint.textSize = (badgeHeight * 0.68f).coerceIn(10f, 17f)
                val fm = textPaint.fontMetrics
                val textCenterY = pillRect.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(mark.elementIndex.toString(), pillRect.centerX(), textCenterY, textPaint)
            }
            out
        } catch (_: Throwable) {
            null
        }
    }
}
