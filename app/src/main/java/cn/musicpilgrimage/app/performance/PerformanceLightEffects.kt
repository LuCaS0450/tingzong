package cn.musicpilgrimage.app.performance

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private val IvoryKeyTone = Color(0xFFF8F6F0)
private val IvoryGlowWarm = Color(0xFFFFEED0)
private val IvoryHotCore = Color(0xFFFFF4E8)
private val ApricotIvory = Color(0xFFFBE3D2)
private val CoralWarm = Color(0xFFEC7555)
private val CoralGold = Color(0xFFF69973)
private val VermilionBright = Color(0xFFD94A38)
private val VermilionGlow = Color(0xFFED654C)
private val CrimsonCleanTail = Color(0xFFC2352B)
private val CrimsonActiveTail = Color(0xFFD94436)
private val CrimsonAnodizedBase = Color(0xFFBD382B)
private val CrimsonAnodizedBright = Color(0xFFDF4F40)
private val CrimsonEmber = Color(0xFFEF6A56)

internal const val NoteWidthRatio = 0.78f

internal fun DrawScope.performanceReceptor(x: Float, line: Float, w: Float, color: Color, pressed: Boolean) {
    val half = w * 0.44f
    if (!pressed) return
    val beamTop = (line - 96.dp.toPx()).coerceAtLeast(0f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                IvoryGlowWarm.copy(alpha = 0.045f),
                IvoryKeyTone.copy(alpha = 0.14f),
            ),
            startY = beamTop,
            endY = line,
        ),
        topLeft = Offset(x - half, beamTop),
        size = Size(half * 2f, line - beamTop),
    )
    drawRect(
        color = IvoryKeyTone.copy(alpha = 0.85f),
        topLeft = Offset(x - half * 0.86f, line - 1.5.dp.toPx()),
        size = Size(half * 1.72f, 2.5.dp.toPx()),
    )
    if (color.alpha > 0f) {
        drawRect(
            color = CrimsonAnodizedBright.copy(alpha = 0.34f),
            topLeft = Offset(x - half * 0.70f, line - 4.dp.toPx()),
            size = Size(half * 1.40f, 1.5.dp.toPx()),
        )
    }
}

/**
 * Unified full-width (78% lane width, matching tap notes) Bauhaus hold pillar
 * with smooth vertical sustain gradient, subtle metallic bevel, start-edge ivory lip,
 * and dual-phase travelling inner light waves while actively held.
 */
internal fun DrawScope.performanceRibbon(
    x: Float,
    top: Float,
    bottom: Float,
    w: Float,
    color: Color,
    holding: Boolean,
    timeUs: Long = 0L,
    motion: Boolean = true,
    alphaScale: Float = 1f,
) {
    if (bottom <= top) return
    val alpha = alphaScale.coerceIn(0f, 1f)
    if (alpha <= 0.01f) return

    val rw = (w * NoteWidthRatio).coerceAtLeast(10.dp.toPx())
    val left = x - rw * 0.5f
    val height = bottom - top
    val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
    val tSec = timeUs / 1_000_000.0
    val breath = if (holding && motion) (0.86f + 0.14f * sin(tSec * 9.5).toFloat()) else 1f

    // 1. Crisp 11 o'clock cast shadow matching tap notes.
    drawRoundRect(
        color = Color(0xFF090B0A).copy(alpha = 0.48f * alpha),
        topLeft = Offset(left + 1.5.dp.toPx(), top + 2.5.dp.toPx()),
        size = Size(rw, height),
        cornerRadius = radius,
    )

    // 2. Subtle outer warm glow while actively held.
    if (holding) {
        val auraPad = 4.dp.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(
                0.0f to CrimsonActiveTail.copy(alpha = 0.14f * breath * alpha),
                0.55f to CoralWarm.copy(alpha = 0.22f * breath * alpha),
                1.0f to IvoryGlowWarm.copy(alpha = 0.30f * breath * alpha),
                startY = top,
                endY = bottom,
            ),
            topLeft = Offset(left - auraPad, top - 1.dp.toPx()),
            size = Size(rw + auraPad * 2f, height + 3.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )
    }

    // 3. Pure, luminous vertical gradient with zero muddiness or dark-black tail:
    //    clean Swiss crimson tail -> luminous vermilion -> warm coral -> apricot ivory onset.
    val bodyBrush = if (holding) {
        Brush.verticalGradient(
            0.0f to CrimsonActiveTail.copy(alpha = 0.96f * alpha),
            0.36f to VermilionGlow.copy(alpha = 0.98f * alpha),
            0.74f to CoralGold.copy(alpha = 0.99f * alpha),
            1.0f to IvoryHotCore.copy(alpha = alpha),
            startY = top,
            endY = bottom,
        )
    } else {
        Brush.verticalGradient(
            0.0f to CrimsonCleanTail.copy(alpha = 0.94f * alpha),
            0.38f to VermilionBright.copy(alpha = 0.97f * alpha),
            0.76f to CoralWarm.copy(alpha = 0.99f * alpha),
            1.0f to ApricotIvory.copy(alpha = alpha),
            startY = top,
            endY = bottom,
        )
    }
    drawRoundRect(
        brush = bodyBrush,
        topLeft = Offset(left, top),
        size = Size(rw, height),
        cornerRadius = radius,
    )

    // 4. Clean 11 o'clock specular sheen & internal travelling shimmer waves (no black edge darkening).
    clipRect(left = left, top = top, right = left + rw, bottom = bottom) {
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.White.copy(alpha = (if (holding) 0.18f else 0.12f) * alpha),
                0.24f to Color.White.copy(alpha = (if (holding) 0.08f else 0.04f) * alpha),
                0.65f to Color.Transparent,
                1.0f to Color.Transparent,
                startX = left,
                endX = left + rw,
            ),
            topLeft = Offset(left, top),
            size = Size(rw, height),
        )

        // Delicate dual-phase upward travelling shimmer bands while holding.
        if (holding && motion && height > 12.dp.toPx()) {
            val travelSpan = height + 72.dp.toPx()
            for (wave in 0..1) {
                val period = if (wave == 0) 0.72 else 1.18
                val phaseOffset = if (wave == 0) 0.0 else 0.43
                val phase = ((tSec / period + phaseOffset) % 1.0).toFloat()
                val bandCenterY = bottom + 24.dp.toPx() - phase * travelSpan
                val bandHalfH = (if (wave == 0) 28.dp else 18.dp).toPx()
                val fadeNearTail = (1f - phase * 0.75f).coerceIn(0.15f, 1f)
                val peakAlpha = (if (wave == 0) 0.34f else 0.22f) * fadeNearTail * alpha
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to IvoryGlowWarm.copy(alpha = peakAlpha * 0.65f),
                        0.55f to IvoryHotCore.copy(alpha = peakAlpha),
                        1.0f to Color.Transparent,
                        startY = bandCenterY - bandHalfH,
                        endY = bandCenterY + bandHalfH,
                    ),
                    topLeft = Offset(left, bandCenterY - bandHalfH),
                    size = Size(rw, bandHalfH * 2f),
                )
            }
            // Fine inner vertical side rails pulsing softly with sustain energy.
            val railBrush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.45f to IvoryGlowWarm.copy(alpha = 0.22f * breath * alpha),
                1.0f to IvoryHotCore.copy(alpha = 0.68f * breath * alpha),
                startY = top,
                endY = bottom,
            )
            val railW = 1.25.dp.toPx()
            drawRect(brush = railBrush, topLeft = Offset(left + 1.dp.toPx(), top), size = Size(railW, height))
            drawRect(brush = railBrush, topLeft = Offset(left + rw - railW - 1.dp.toPx(), top), size = Size(railW, height))
        }

        // Crisp Bauhaus start-edge ivory strike lip at the onset bottom of the unified pillar.
        val lipHeight = (if (holding) 2.5.dp else 3.5.dp).toPx().coerceAtMost(height)
        drawRect(
            color = (if (holding) IvoryHotCore else IvoryKeyTone).copy(alpha = (if (holding) 0.96f else 0.92f) * alpha),
            topLeft = Offset(left, bottom - lipHeight),
            size = Size(rw, lipHeight),
        )
        // Subtle center guide notch just above the start lip when approaching.
        if (!holding && height > 14.dp.toPx()) {
            drawLine(
                color = IvoryKeyTone.copy(alpha = 0.36f * alpha),
                start = Offset(left + rw * 0.28f, bottom - lipHeight - 4.dp.toPx()),
                end = Offset(left + rw * 0.72f, bottom - lipHeight - 4.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    // 5. Crisp hairline border from clean warm tail to ivory onset edge.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to ApricotIvory.copy(alpha = (if (holding) 0.42f else 0.26f) * alpha),
            0.50f to (if (holding) IvoryGlowWarm else color).copy(alpha = (if (holding) 0.56f else 0.34f) * alpha),
            1.0f to IvoryKeyTone.copy(alpha = (if (holding) 0.90f else 0.68f) * alpha),
            startY = top,
            endY = bottom,
        ),
        topLeft = Offset(left, top),
        size = Size(rw, height),
        cornerRadius = radius,
        style = Stroke(width = (if (holding) 1.1.dp else 0.85.dp).toPx()),
    )
}

/**
 * Refined multi-layered Bauhaus sustain light effect anchored on the judgment line:
 * - Soft elliptical core glow + upward lane radiance
 * - Dual-phase expanding geometric Bauhaus ripples
 * - 12 deterministic rising ember/ivory micro-sparks (zero per-frame heap allocation)
 */
internal fun DrawScope.performanceSustain(
    x: Float,
    line: Float,
    w: Float,
    color: Color,
    fraction: Float,
    timeUs: Long,
    motion: Boolean,
) {
    val rw = (w * NoteWidthRatio).coerceAtLeast(10.dp.toPx())
    val tSec = timeUs / 1_000_000.0
    val pulse = if (motion) (0.86f + 0.14f * sin(tSec * 10.5).toFloat()) else 0.94f
    val intensity = (0.78f + 0.22f * fraction.coerceIn(0f, 1f)) * pulse

    // Layer 1: Gentle upward energy radiance aligned strictly with the pillar width.
    val glowHeight = 68.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.45f to CrimsonAnodizedBright.copy(alpha = 0.14f * intensity),
            0.84f to CoralGold.copy(alpha = 0.24f * intensity),
            1.0f to IvoryGlowWarm.copy(alpha = 0.34f * intensity),
            startY = line - glowHeight,
            endY = line,
        ),
        topLeft = Offset(x - rw * 0.56f, line - glowHeight),
        size = Size(rw * 1.12f, glowHeight),
    )

    // Layer 2: Multi-stop feathered contact glow directly on the strike line intersection.
    val outerRadius = rw * 0.72f
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to IvoryHotCore.copy(alpha = 0.52f * intensity),
            0.28f to CoralGold.copy(alpha = 0.34f * intensity),
            0.62f to CrimsonAnodizedBright.copy(alpha = 0.14f * intensity),
            1.0f to Color.Transparent,
            center = Offset(x, line),
            radius = outerRadius,
        ),
        radius = outerRadius,
        center = Offset(x, line),
    )

    // Crisp illuminated contact bar spanning the active note width on the pure ivory strike line.
    drawRect(
        brush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.14f to IvoryHotCore.copy(alpha = 0.92f * pulse),
            0.50f to Color.White.copy(alpha = pulse),
            0.86f to IvoryHotCore.copy(alpha = 0.92f * pulse),
            1.0f to Color.Transparent,
            startX = x - rw * 0.62f,
            endX = x + rw * 0.62f,
        ),
        topLeft = Offset(x - rw * 0.62f, line - 1.75.dp.toPx()),
        size = Size(rw * 1.24f, 3.5.dp.toPx()),
    )

    if (!motion) {
        // Calm reduced-motion fallback: crisp static Bauhaus focus frame around contact point.
        drawRoundRect(
            color = IvoryKeyTone.copy(alpha = 0.55f),
            topLeft = Offset(x - rw * 0.54f, line - 9.dp.toPx()),
            size = Size(rw * 1.08f, 18.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
        return
    }

    // Layer 3: Dual-phase alternating expanding Bauhaus geometric ripples.
    val ripplePeriod = 0.78
    for (r in 0..1) {
        val phase = ((tSec / ripplePeriod + r * 0.5) % 1.0).toFloat()
        val easeOut = 1f - (1f - phase) * (1f - phase)
        val fade = (1f - phase).pow(1.75f)
        val boxW = rw * (0.94f + easeOut * 0.34f)
        val boxH = (5f + easeOut * 23f).dp.toPx()
        val strokeW = (1.55f - easeOut * 0.95f).dp.toPx().coerceAtLeast(0.5.dp.toPx())
        val rippleColor = if (r == 0) IvoryKeyTone else CoralGold
        drawRoundRect(
            color = rippleColor.copy(alpha = 0.68f * fade),
            topLeft = Offset(x - boxW * 0.5f, line - boxH * 0.52f),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = strokeW),
        )
    }

    // Layer 4: 12 deterministic rising micro-sparks (ivory & warm coral) with organic drift and smooth fade.
    for (i in 0 until 12) {
        val period = 0.64 + (i % 4) * 0.14
        val phaseSeed = (i * 0.371) % 1.0
        val life = ((tSec / period + phaseSeed) % 1.0).toFloat()
        // Smooth fade-in over first 14%, graceful quadratic fade-out over the rest.
        val fadeIn = (life / 0.14f).coerceIn(0f, 1f)
        val fadeOut = (1f - life).pow(1.65f)
        val sparkAlpha = fadeIn * fadeOut * 0.92f
        if (sparkAlpha <= 0.02f) continue

        // Deterministic horizontal lane distribution across the pillar width + gentle sinusoidal sway.
        val normalizedSlot = (((i * 7 + 3) % 12) / 11f) - 0.5f // [-0.5, 0.5]
        val swayAmp = (2.5f + (i % 3) * 1.8f).dp.toPx()
        val sway = sin(life * 2.0 * PI + i * 1.3).toFloat() * swayAmp
        val sx = x + normalizedSlot * rw * 0.88f + sway

        val maxRise = (38f + (i % 5) * 15f).dp.toPx()
        val riseProgress = 1f - (1f - life).pow(1.35f)
        val sy = line - 3.dp.toPx() - riseProgress * maxRise

        val baseSize = (1.6f + (i % 3) * 0.55f).dp.toPx() * (1f - life * 0.35f)
        val sparkTone = when (i % 3) {
            0 -> IvoryHotCore
            1 -> CoralGold
            else -> if (color.alpha > 0.5f) IvoryGlowWarm else CrimsonEmber
        }

        // Soft halo behind each micro-spark for refined luminance.
        drawCircle(
            color = sparkTone.copy(alpha = sparkAlpha * 0.28f),
            radius = baseSize * 2.1f,
            center = Offset(sx, sy),
        )
        // Crisp Bauhaus square/sliver core.
        drawRect(
            color = sparkTone.copy(alpha = sparkAlpha),
            topLeft = Offset(sx - baseSize * 0.5f, sy - baseSize * 0.5f),
            size = Size(baseSize, baseSize * (if (i % 2 == 0) 1.35f else 1f)),
        )
    }
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
    val isHoldComplete = event.kind == FeedbackKind.HOLD_COMPLETE
    val durationUs = if (isHoldComplete) 460_000f else 340_000f
    val age = ((timeUs - event.atUs).coerceAtLeast(0L) / durationUs)
    if (age >= 1f) return
    val p = if (motion) age else 0.32f
    val easeOut = 1f - (1f - p) * (1f - p)
    val alpha = (1f - age).let { it * it }
    val rw = (w * NoteWidthRatio).coerceAtLeast(10.dp.toPx())

    if (isHoldComplete) {
        // Layered Bauhaus completion burst: radiant flash + staggered dual frames + 6 symmetric micro-shards.
        val burstRadius = rw * (0.55f + easeOut * 0.45f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to IvoryHotCore.copy(alpha = 0.55f * alpha),
                0.45f to CoralGold.copy(alpha = 0.26f * alpha),
                1.0f to Color.Transparent,
                center = Offset(x, line),
                radius = burstRadius,
            ),
            radius = burstRadius,
            center = Offset(x, line),
        )

        // Horizontal light arc sweeping outward along the judgment line.
        val arcHalfW = rw * (0.52f + easeOut * 0.42f)
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.25f to IvoryHotCore.copy(alpha = 0.85f * alpha),
                0.50f to Color.White.copy(alpha = 0.95f * alpha),
                0.75f to IvoryHotCore.copy(alpha = 0.85f * alpha),
                1.0f to Color.Transparent,
                startX = x - arcHalfW,
                endX = x + arcHalfW,
            ),
            topLeft = Offset(x - arcHalfW, line - 2.dp.toPx()),
            size = Size(arcHalfW * 2f, 4.dp.toPx()),
        )

        // Inner sharp ivory expanding frame.
        val innerW = rw * (0.96f + easeOut * 0.38f)
        val innerH = (12f + easeOut * 28f).dp.toPx()
        drawRoundRect(
            color = IvoryHotCore.copy(alpha = 0.90f * alpha),
            topLeft = Offset(x - innerW * 0.5f, line - innerH * 0.56f),
            size = Size(innerW, innerH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = (1.6f - easeOut * 0.9f).dp.toPx().coerceAtLeast(0.6.dp.toPx())),
        )

        // Staggered outer crimson-coral frame.
        val outerP = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)
        if (outerP > 0f) {
            val outerEase = 1f - (1f - outerP) * (1f - outerP)
            val outerAlpha = (1f - outerP) * (1f - outerP)
            val outerW = rw * (1.02f + outerEase * 0.52f)
            val outerH = (16f + outerEase * 38f).dp.toPx()
            drawRoundRect(
                color = CoralGold.copy(alpha = 0.72f * outerAlpha),
                topLeft = Offset(x - outerW * 0.5f, line - outerH * 0.56f),
                size = Size(outerW, outerH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = Stroke(width = (1.3f - outerEase * 0.7f).dp.toPx().coerceAtLeast(0.5.dp.toPx())),
            )
        }

        // Six symmetric Bauhaus micro-shards dispersing gracefully upward/outward.
        for (k in 0 until 6) {
            val angle = -PI * (0.14 + k * 0.144) // fan across upper arc
            val dist = (10f + easeOut * (32f + (k % 2) * 14f)).dp.toPx()
            val fx = x + cos(angle).toFloat() * dist * 1.15f
            val fy = line + sin(angle).toFloat() * dist
            val sq = (2.8f - easeOut * 1.2f).dp.toPx().coerceAtLeast(1.2.dp.toPx())
            val tone = if (k % 2 == 0) IvoryHotCore else CoralGold
            drawRect(
                color = tone.copy(alpha = 0.92f * alpha),
                topLeft = Offset(fx - sq * 0.5f, fy - sq * 0.5f),
                size = Size(sq, sq),
            )
        }
        return
    }

    val tone = when {
        !event.kind.success -> CrimsonAnodizedBright
        event.grade == Judgment.PERFECT -> IvoryKeyTone
        else -> IvoryGlowWarm
    }
    val boxW = rw * (0.78f + easeOut * 0.34f)
    val boxH = (14f + easeOut * 20f).dp.toPx()
    drawRoundRect(
        color = tone.copy(alpha = 0.78f * alpha),
        topLeft = Offset(x - boxW * 0.5f, line - boxH * 0.55f),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        style = Stroke(width = 1.4.dp.toPx()),
    )
    drawRect(
        color = (if (event.kind.success) CrimsonAnodizedBase else color).copy(alpha = 0.22f * alpha),
        topLeft = Offset(x - rw * 0.52f, line - 3.dp.toPx()),
        size = Size(rw * 1.04f, 5.dp.toPx()),
    )
    for (i in -1..1) {
        if (i == 0) continue
        val fx = x + i * (rw * (0.26f + easeOut * 0.28f))
        val fy = line - (6f + easeOut * 22f).dp.toPx()
        val sq = (2.6f - easeOut * 0.8f).dp.toPx()
        drawRect(
            color = (if (i < 0) IvoryKeyTone else CrimsonAnodizedBright).copy(alpha = 0.84f * alpha),
            topLeft = Offset(fx - sq * 0.5f, fy - sq * 0.5f),
            size = Size(sq, sq),
        )
    }
}
