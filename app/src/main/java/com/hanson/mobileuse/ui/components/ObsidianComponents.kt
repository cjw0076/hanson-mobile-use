package com.hanson.mobileuse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.ui.theme.ObsidianColors

/**
 * Ahaboard `.lb-paper` 24dp radial dot-grid background canvas.
 */
@Composable
fun AhaboardDotGridBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor = ObsidianColors.Background
    val dotColor = ObsidianColors.DotGridColor

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepPx = 24.dp.toPx()
            val dotRadiusPx = 1.15.dp.toPx()
            var x = stepPx / 2f
            while (x < size.width) {
                var y = stepPx / 2f
                while (y < size.height) {
                    drawCircle(
                        color = dotColor,
                        radius = dotRadiusPx,
                        center = Offset(x, y)
                    )
                    y += stepPx
                }
                x += stepPx
            }
        }
        content()
    }
}

/**
 * Ahaboard `.brand-card` with optional 3dp solid tactile bottom ink lip (`0 3px 0 0 var(--ink)`).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    borderColor: Color = ObsidianColors.GlassBorder,
    containerColor: Color = ObsidianColors.Surface,
    tactileBottomLip: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val lipColor = ObsidianColors.Accent
    Surface(
        modifier = modifier
            .then(
                if (tactileBottomLip) {
                    Modifier
                        .padding(bottom = 3.dp)
                        .drawBehind {
                            val radiusPx = cornerRadius.toPx()
                            val lipPx = 3.dp.toPx()
                            drawRoundRect(
                                color = lipColor,
                                topLeft = Offset(0f, lipPx),
                                size = Size(size.width, size.height),
                                cornerRadius = CornerRadius(radiusPx, radiusPx)
                            )
                        }
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .border(
                BorderStroke(
                    width = if (tactileBottomLip) 1.5.dp else 1.dp,
                    color = if (tactileBottomLip) ObsidianColors.Accent else borderColor
                ),
                shape
            ),
        shape = shape,
        color = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    label: String,
    active: Boolean,
    activeColor: Color = ObsidianColors.Accent,
    inactiveColor: Color = ObsidianColors.TextMuted,
    onClick: (() -> Unit)? = null
) {
    val tint = if (active) activeColor else inactiveColor
    val bg = if (active) ObsidianColors.SurfaceHigh else ObsidianColors.Surface
    val shape = RoundedCornerShape(999.dp)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, ObsidianColors.GlassBorder, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = ObsidianColors.TextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CodeBadge(text: String, color: Color = ObsidianColors.TextPrimary) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
