package com.hanson.mobileuse.accessibility

import kotlin.math.roundToInt

/**
 * Google Artemis (`third_party/mobile_use`) standardizes screen coordinates on a
 * [0..1000, 0..1000] grid so vision-language models operate independently of physical DPI.
 */
object CoordinateNormalizer {
    private const val NORM_SCALE = 1000.0

    fun pixelToNormalized(
        pxX: Int,
        pxY: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        val safeW = screenWidth.coerceAtLeast(1)
        val safeH = screenHeight.coerceAtLeast(1)
        val normX = ((pxX.toDouble() / safeW) * NORM_SCALE).roundToInt().coerceIn(0, 1000)
        val normY = ((pxY.toDouble() / safeH) * NORM_SCALE).roundToInt().coerceIn(0, 1000)
        return normX to normY
    }

    fun normalizedToPixel(
        normX: Int,
        normY: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        val safeW = screenWidth.coerceAtLeast(1)
        val safeH = screenHeight.coerceAtLeast(1)
        val clampedX = normX.coerceIn(0, 1000)
        val clampedY = normY.coerceIn(0, 1000)
        val pxX = ((clampedX / NORM_SCALE) * safeW).roundToInt().coerceIn(0, safeW - 1)
        val pxY = ((clampedY / NORM_SCALE) * safeH).roundToInt().coerceIn(0, safeH - 1)
        return pxX to pxY
    }

    /**
     * Computes directional swipe coordinates following the Google Artemis Operator specification:
     * - Maintains at least 40% visual anchor overlap so the user/model doesn't lose context.
     * - Note on direction semantics:
     *   - "up" (or "scroll_down"): swipe finger UP (from 72% Y to 28% Y) to reveal lower content.
     *   - "down" (or "scroll_up"): swipe finger DOWN (from 28% Y to 72% Y) to reveal upper content.
     *   - "left": swipe finger LEFT (from 80% X to 20% X) to advance horizontal pager right.
     *   - "right": swipe finger RIGHT (from 20% X to 80% X) to go back on horizontal pager.
     */
    data class SwipeVector(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    )

    fun computeDirectionalSwipe(
        direction: String,
        screenWidth: Int,
        screenHeight: Int,
        anchorCenterX: Int? = null,
        anchorCenterY: Int? = null
    ): SwipeVector {
        val safeW = screenWidth.coerceAtLeast(100)
        val safeH = screenHeight.coerceAtLeast(100)
        val cx = (anchorCenterX ?: (safeW / 2)).coerceIn((safeW * 0.15).roundToInt(), (safeW * 0.85).roundToInt())
        val cy = (anchorCenterY ?: (safeH / 2)).coerceIn((safeH * 0.15).roundToInt(), (safeH * 0.85).roundToInt())

        return when (direction.trim().lowercase()) {
            "up", "scroll_down" -> SwipeVector(
                startX = cx,
                startY = (safeH * 0.72).roundToInt(),
                endX = cx,
                endY = (safeH * 0.28).roundToInt()
            )
            "down", "scroll_up" -> SwipeVector(
                startX = cx,
                startY = (safeH * 0.28).roundToInt(),
                endX = cx,
                endY = (safeH * 0.72).roundToInt()
            )
            "left", "scroll_right" -> SwipeVector(
                startX = (safeW * 0.82).roundToInt(),
                startY = cy,
                endX = (safeW * 0.18).roundToInt(),
                endY = cy
            )
            "right", "scroll_left" -> SwipeVector(
                startX = (safeW * 0.18).roundToInt(),
                startY = cy,
                endX = (safeW * 0.82).roundToInt(),
                endY = cy
            )
            else -> SwipeVector(
                startX = cx,
                startY = (safeH * 0.72).roundToInt(),
                endX = cx,
                endY = (safeH * 0.28).roundToInt()
            )
        }
    }
}
