package cn.musicpilgrimage.app.performance

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val IvoryKeyTone = Color(0xFFF8F6F0)
private val IvoryGlowWarm = Color(0xFFFFEED0)
private val CrimsonAnodizedDeep = Color(0xFF8A261C)
private val CrimsonAnodizedBase = Color(0xFFBD382B)
private val CrimsonAnodizedBright = Color(0xFFD64B3D)

internal fun DrawScope.performanceReceptor(x: Float, line: Float, w: Float, color: Color, pressed: Boolean) {
    val half = w * 0.46f
    if (!pressed) return
    val beamTop = (line - 112.dp.toPx()).coerceAtLeast(0f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                IvoryGlowWarm.copy(alpha = 0.06f),
                IvoryKeyTone.copy(alpha = 0.16f),
            ),
            startY = beamTop,
            endY = line,
        ),
        topLeft = Offset(x - half, beamTop),
        size = Size(half * 2f, line - beamTop),
    )
    drawRect(
        color = CrimsonAnodizedBright.copy(alpha = 0.85f),
        topLeft = Offset(x - half, line - 2.dp.toPx()),
        size = Size(half * 2f, 2.dp.toPx()),
    )
    // Preserve color parameter hook for calm mode subtle modulation without adding off-palette hues.
    if (color.alpha > 0f) {
        drawRect(
            color = IvoryKeyTone.copy(alpha = 0.28f),
            topLeft = Offset(x - half * 0.72f, line - 4.dp.toPx()),
            size = Size(half * 1.44f, 1.5.dp.toPx()),
        )
    }
}

internal fun DrawScope.performanceRibbon(x: Float, top: Float, bottom: Float, w: Float, color: Color, holding: Boolean) {
    if (bottom <= top) return
    val rw = (w * if (holding) 0.50f else 0.42f).coerceAtLeast(8.dp.toPx())
    val left = x - rw * 0.5f
    val height = bottom - top
    val radius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
    drawRoundRect(
        color = Color(0xFF0A0C0B).copy(alpha = 0.48f),
        topLeft = Offset(left + 1.5.dp.toPx(), top + 2.dp.toPx()),
        size = Size(rw, height),
        cornerRadius = radius,
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = if (holding) {
                listOf(CrimsonAnodizedBase, CrimsonAnodizedBright, CrimsonAnodizedBase)
            } else {
                listOf(CrimsonAnodizedDeep, CrimsonAnodizedBase, CrimsonAnodizedDeep)
            },
            startX = left,
            endX = left + rw,
        ),
        topLeft = Offset(left, top),
        size = Size(rw, height),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = IvoryKeyTone.copy(alpha = if (holding) 0.55f else 0.26f),
        topLeft = Offset(left, top),
        size = Size(rw, height),
        cornerRadius = radius,
        style = Stroke(width = if (holding) 1.1.dp.toPx() else 0.75.dp.toPx()),
    )
    // Crisp central ivory guide wire inside the anodized red ribbon.
    val guideAlpha = if (holding) 0.92f else 0.58f
    drawLine(
        color = if (holding) IvoryKeyTone.copy(alpha = guideAlpha) else color.copy(alpha = 0.45f),
        start = Offset(x, top + 2.dp.toPx()),
        end = Offset(x, bottom - 2.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
}

internal fun DrawScope.performanceSustain(
    x: Float,
    line: Float,
    w: Float,
    color: Color,
    fraction: Float,
    timeUs: Long,
    motion: Boolean,
) {
    val pulse = if (motion) 0.85f + 0.15f * sin(timeUs / 120_000.0 * PI).toFloat() else 0.95f
    val barHalf = w * 0.34f
    val barY = line - 14.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                CrimsonAnodizedBright.copy(alpha = 0.22f * pulse),
                IvoryGlowWarm.copy(alpha = 0.18f * pulse),
            ),
            startY = line - 44.dp.toPx(),
            endY = line,
        ),
        topLeft = Offset(x - w * 0.44f, line - 44.dp.toPx()),
        size = Size(w * 0.88f, 44.dp.toPx()),
    )
    // Dark recessed track + crisp ivory progress bar placed safely above keycap top edge.
    drawRoundRect(
        color = Color(0xFF0E100F).copy(alpha = 0.85f),
        topLeft = Offset(x - barHalf, barY - 2.dp.toPx()),
        size = Size(barHalf * 2f, 4.dp.toPx()),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
    )
    val fillW = (barHalf * 2f * fraction.coerceIn(0f, 1f)).coerceAtLeast(1.dp.toPx())
    drawRoundRect(
        color = if (color.alpha > 0.8f) IvoryKeyTone else IvoryGlowWarm,
        topLeft = Offset(x - barHalf, barY - 2.dp.toPx()),
        size = Size(fillW, 4.dp.toPx()),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
    )
}

internal fun DrawScope.performanceImpact(
    event: MusicFeedback,
    timeUs: Long,
    x: Float,
    line: Float,
    w: Float,
    color: Color,
    motion: Boolean,
) {
    val age = ((timeUs - event.atUs).coerceAtLeast(0L) / 360_000f)
    if (age >= 1f) return
    val p = if (motion) age else 0.32f
    val alpha = (1f - age).let { it * it }
    val tone = when {
        !event.kind.success -> CrimsonAnodizedBright
        event.grade == Judgment.PERFECT -> IvoryKeyTone
        else -> IvoryGlowWarm
    }
    val boxW = w * (0.52f + p * 0.34f)
    val boxH = (16f + p * 22f).dp.toPx()
    drawRect(
        color = tone.copy(alpha = 0.82f * alpha),
        topLeft = Offset(x - boxW * 0.5f, line - boxH * 0.65f),
        size = Size(boxW, boxH),
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawRect(
        color = (if (event.kind.success) CrimsonAnodizedBase else color).copy(alpha = 0.24f * alpha),
        topLeft = Offset(x - w * 0.42f, line - 5.dp.toPx()),
        size = Size(w * 0.84f, 6.dp.toPx()),
    )
    // Geometric Bauhaus micro-fragments along the judgment plane.
    for (i in -1..1) {
        if (i == 0) continue
        val fx = x + i * (w * (0.20f + p * 0.24f))
        val fy = line - (8f + p * 24f).dp.toPx()
        val sq = 3.dp.toPx()
        drawRect(
            color = (if (i < 0) IvoryKeyTone else CrimsonAnodizedBright).copy(alpha = 0.88f * alpha),
            topLeft = Offset(fx - sq * 0.5f, fy - sq * 0.5f),
            size = Size(sq, sq),
        )
    }
}
