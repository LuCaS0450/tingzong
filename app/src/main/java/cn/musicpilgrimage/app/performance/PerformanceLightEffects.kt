package cn.musicpilgrimage.app.performance

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private val IvoryKeyTone = Color(0xFFF8F6F0)
private val IvoryGlowWarm = Color(0xFFFFEED0)
private val IvoryHotCore = Color(0xFFFFF4E8)
private val SilkIvory = Color(0xFFFFF0E2)
private val SilkBlush = Color(0xFFFAD0BA)
private val SoftPeach = Color(0xFFF59A74)
private val ApricotIvory = Color(0xFFFCE6D4)
private val CoralWarm = Color(0xFFEB6B4F)
private val CoralGold = Color(0xFFF69973)
private val RoseLuminance = Color(0xFFE2475D)
private val RubyRich = Color(0xFFB4253B)
private val VelvetCrimson = Color(0xFFCF3636)
private val VermilionBright = Color(0xFFDC4535)
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
 * rendered with an Apple Music-inspired organic multi-blob mesh gradient.
 * Each hold note gets a deterministic per-note seed so color fields feel organic and non-repetitive,
 * and actively transforms (faster Lissajous fluid swirl, blooming luminous cores, shifting warmth) while held.
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
    seed: Int = 0,
    heldFraction: Float = 0f,
) {
    if (bottom <= top) return
    val alpha = alphaScale.coerceIn(0f, 1f)
    if (alpha <= 0.01f) return

    val rw = (w * NoteWidthRatio).coerceAtLeast(10.dp.toPx())
    val left = x - rw * 0.5f
    val height = bottom - top
    val cornerPx = 3.dp.toPx()
    val radius = CornerRadius(cornerPx, cornerPx)
    val tSec = timeUs / 1_000_000.0
    val frac = heldFraction.coerceIn(0f, 1f)
    val holdBoost = if (holding) (0.65f + 0.35f * frac) else 0f
    val breath = if (holding && motion) (0.90f + 0.10f * sin(tSec * 6.8 + seed * 0.7).toFloat()) else 1f

    // 1. Soft 11 o'clock cast shadow matching tap notes.
    drawRoundRect(
        color = Color(0xFF090B0A).copy(alpha = 0.42f * alpha),
        topLeft = Offset(left + 1.5.dp.toPx(), top + 2.5.dp.toPx()),
        size = Size(rw, height),
        cornerRadius = radius,
    )

    // 2. Feathered outer warm aura bloom while actively held.
    if (holding) {
        val auraPad = (4.dp + (frac * 2.5f).dp).toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(
                0.0f to RoseLuminance.copy(alpha = 0.12f * breath * alpha),
                0.48f to CoralWarm.copy(alpha = (0.18f + 0.09f * frac) * breath * alpha),
                1.0f to SilkIvory.copy(alpha = (0.24f + 0.12f * frac) * breath * alpha),
                startY = top,
                endY = bottom,
            ),
            topLeft = Offset(left - auraPad, top - 1.dp.toPx()),
            size = Size(rw + auraPad * 2f, height + 3.dp.toPx()),
            cornerRadius = CornerRadius(cornerPx + auraPad * 0.5f, cornerPx + auraPad * 0.5f),
        )
    }

    // 3. True rounded clip path for silky Apple Music-style Gaussian multi-blob fluid mesh.
    val pillarClip = Path().apply {
        addRoundRect(RoundRect(left, top, left + rw, bottom, cornerPx, cornerPx))
    }
    clipPath(pillarClip) {
        // Warm, luminous diagonal silk base with gentle organic drift.
        val drift = if (motion) sin(tSec * (if (holding) 1.9 else 0.55) + seed * 0.9).toFloat() * rw * 0.45f else 0f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    lerp(RubyRich, RoseLuminance, holdBoost * 0.65f).copy(alpha = 0.97f * alpha),
                    lerp(VelvetCrimson, CoralWarm, holdBoost * 0.60f).copy(alpha = 0.98f * alpha),
                    lerp(CoralWarm, SoftPeach, holdBoost * 0.70f).copy(alpha = alpha),
                ),
                start = Offset(left - drift, top),
                end = Offset(left + rw + drift, bottom),
            ),
            topLeft = Offset(left, top),
            size = Size(rw, height),
        )

        // Six oversized Gaussian-falloff color clouds wandering across and slightly beyond pillar edges,
        // driven by irrational frequency ratios so every note has an organic, non-repeating Apple Music flow.
        val speedMul = when {
            !motion -> 0.0
            holding -> 1.85
            else -> 0.42
        }
        val s = seed * 1.6180339887
        val meshSpan = maxOf(rw * 1.55f, minOf(height * 0.68f, rw * 2.40f))

        for (b in 0 until 6) {
            val freqX = when (b) {
                0 -> 0.71; 1 -> 1.03; 2 -> 1.37; 3 -> 1.73; 4 -> 0.89; else -> 1.29
            }
            val freqY = when (b) {
                0 -> 0.97; 1 -> 0.67; 2 -> 1.19; 3 -> 0.83; 4 -> 1.49; else -> 1.07
            }
            val phaseX = s * (b + 1.17) + b * 1.91 + tSec * speedMul * freqX
            val phaseY = s * (b + 2.31) + b * 1.33 + tSec * speedMul * freqY

            // Allow cloud centers to drift gently past the pillar sides so internal color transitions stay silky smooth.
            val normX = 0.5f + 0.68f * sin(phaseX).toFloat()
            val anchorY = (b + 0.5f) / 6f
            val wanderY = (if (holding) 0.28f else 0.14f) * cos(phaseY).toFloat()
            val upwardBloom = if (holding && b >= 3) -frac * 0.24f else 0f
            val bx = left + rw * normX
            val by = top + height * (anchorY + wanderY + upwardBloom).coerceIn(-0.18f, 1.18f)

            val radBreath = if (holding && motion) (1.10f + 0.16f * sin(phaseX * 0.8 + phaseY * 0.6).toFloat()) else 1.0f
            val br = meshSpan * (0.95f + (b % 3) * 0.24f) * radBreath

            val idleCol = when (b) {
                0 -> RubyRich
                1 -> RoseLuminance
                2 -> VelvetCrimson
                3 -> CoralWarm
                4 -> SoftPeach
                else -> VermilionBright
            }
            val activeCol = when (b) {
                0 -> VelvetCrimson
                1 -> RoseLuminance
                2 -> CoralWarm
                3 -> CoralGold
                4 -> SoftPeach
                else -> RoseLuminance
            }
            val cloudColor = lerp(idleCol, activeCol, holdBoost * 0.75f)
            val peakAlpha = (when (b) {
                0 -> 0.72f
                1 -> 0.74f
                2 -> 0.70f
                3 -> 0.74f
                4 -> 0.56f + 0.14f * holdBoost
                else -> 0.58f + 0.14f * holdBoost
            }) * alpha

            // 6-stop smooth Gaussian falloff eliminates any perceptible circular edge.
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to cloudColor.copy(alpha = peakAlpha),
                    0.22f to cloudColor.copy(alpha = peakAlpha * 0.88f),
                    0.44f to cloudColor.copy(alpha = peakAlpha * 0.60f),
                    0.66f to cloudColor.copy(alpha = peakAlpha * 0.28f),
                    0.84f to cloudColor.copy(alpha = peakAlpha * 0.08f),
                    1.00f to Color.Transparent,
                    center = Offset(bx, by),
                    radius = br,
                ),
                radius = br,
                center = Offset(bx, by),
            )
        }

        // Ultra-subtle translucent glass glaze along the 11 o'clock edge for refined physical depth.
        drawRect(
            brush = Brush.horizontalGradient(
                0.00f to Color.White.copy(alpha = (if (holding) 0.14f else 0.10f) * alpha),
                0.18f to Color.White.copy(alpha = (if (holding) 0.05f else 0.03f) * alpha),
                0.60f to Color.Transparent,
                1.00f to Color.Transparent,
                startX = left,
                endX = left + rw,
            ),
            topLeft = Offset(left, top),
            size = Size(rw, height),
        )

        // Restored dual-phase upward travelling straight shimmer bands & inner rails while holding.
        if (holding && motion && height > 12.dp.toPx()) {
            val travelSpan = height + 72.dp.toPx()
            for (wave in 0..1) {
                val period = if (wave == 0) 0.68 else 1.12
                val phaseOffset = if (wave == 0) 0.0 else 0.47
                val phase = ((tSec / period + phaseOffset) % 1.0).toFloat()
                val bandCenterY = bottom + 24.dp.toPx() - phase * travelSpan
                val bandHalfH = (if (wave == 0) 26.dp else 16.dp).toPx()
                val fadeNearTail = (1f - phase * 0.72f).coerceIn(0.18f, 1f)
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
            val railBrush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.42f to IvoryGlowWarm.copy(alpha = 0.24f * breath * alpha),
                1.0f to IvoryHotCore.copy(alpha = 0.68f * breath * alpha),
                startY = top,
                endY = bottom,
            )
            val railW = 1.25.dp.toPx()
            drawRect(brush = railBrush, topLeft = Offset(left + 1.dp.toPx(), top), size = Size(railW, height))
            drawRect(brush = railBrush, topLeft = Offset(left + rw - railW - 1.dp.toPx(), top), size = Size(railW, height))
        }
    }

    // 4. Subtle translucent rim light wrapping the rounded silhouette cleanly.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to SilkBlush.copy(alpha = (if (holding) 0.42f else 0.22f) * alpha),
            0.55f to (if (holding) SilkIvory else color).copy(alpha = (if (holding) 0.54f else 0.28f) * alpha),
            1.0f to IvoryKeyTone.copy(alpha = (if (holding) 0.86f else 0.62f) * alpha),
            startY = top,
            endY = bottom,
        ),
        topLeft = Offset(left, top),
        size = Size(rw, height),
        cornerRadius = radius,
        style = Stroke(width = (if (holding) 1.dp else 0.75.dp).toPx()),
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
    partnerX: Float? = null,
) {
    val isHoldComplete = event.kind == FeedbackKind.HOLD_COMPLETE
    val isFlick = event.kind == FeedbackKind.FLICK
    val durationUs = when {
        isHoldComplete -> 460_000f
        isFlick -> 380_000f
        event.kind == FeedbackKind.TAP && event.grade == Judgment.PERFECT -> 380_000f
        else -> 320_000f
    }
    val age = ((timeUs - event.atUs).coerceAtLeast(0L) / durationUs)
    if (age >= 1f) return
    val p = if (motion) age else 0.30f
    val easeOut = 1f - (1f - p) * (1f - p)
    val alpha = (1f - age).let { it * it }
    val rw = (w * NoteWidthRatio).coerceAtLeast(10.dp.toPx())

    // Cross-lane Bauhaus resonance string when hitting a chord pair simultaneously.
    if (partnerX != null && event.kind.success) {
        val leftX = minOf(x, partnerX)
        val rightX = maxOf(x, partnerX)
        val vib = if (motion) sin(p * 4.5 * PI).toFloat() * (1f - p) * 2.5.dp.toPx() else 0f
        drawLine(
            color = CrimsonAnodizedBright.copy(alpha = 0.42f * alpha),
            start = Offset(leftX, line + vib),
            end = Offset(rightX, line - vib),
            strokeWidth = (4.5f - easeOut * 2.5f).dp.toPx().coerceAtLeast(1.dp.toPx()),
        )
        drawLine(
            color = IvoryHotCore.copy(alpha = 0.92f * alpha),
            start = Offset(leftX, line),
            end = Offset(rightX, line),
            strokeWidth = (1.8f - easeOut * 0.9f).dp.toPx().coerceAtLeast(0.6.dp.toPx()),
        )
    }

    if (isHoldComplete) {
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
        val innerW = rw * (0.96f + easeOut * 0.38f)
        val innerH = (12f + easeOut * 28f).dp.toPx()
        drawRoundRect(
            color = IvoryHotCore.copy(alpha = 0.90f * alpha),
            topLeft = Offset(x - innerW * 0.5f, line - innerH * 0.56f),
            size = Size(innerW, innerH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = (1.6f - easeOut * 0.9f).dp.toPx().coerceAtLeast(0.6.dp.toPx())),
        )
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
        for (k in 0 until 6) {
            val angle = -PI * (0.14 + k * 0.144)
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

    if (isFlick) {
        // Directional crimson-ivory slash blade along the player's actual swipe vector + trailing micro-sparks.
        val dirX = cos(event.angleRad.toDouble()).toFloat()
        val dirY = sin(event.angleRad.toDouble()).toFloat()
        val perpX = -dirY
        val perpY = dirX

        // Core flash at strike line.
        val flashR = rw * (0.48f + easeOut * 0.36f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to IvoryKeyTone.copy(alpha = 0.58f * alpha),
                0.35f to CrimsonAnodizedBright.copy(alpha = 0.34f * alpha),
                1.0f to Color.Transparent,
                center = Offset(x, line),
                radius = flashR,
            ),
            radius = flashR,
            center = Offset(x, line),
        )

        // Sharp dual-layer directional slash blade.
        val tailLen = (16f + easeOut * 10f).dp.toPx()
        val headLen = (18f + easeOut * 62f).dp.toPx()
        val startPt = Offset(x - dirX * tailLen, line - dirY * tailLen)
        val endPt = Offset(x + dirX * headLen, line + dirY * headLen)
        drawLine(
            color = CrimsonAnodizedBright.copy(alpha = 0.68f * alpha),
            start = startPt,
            end = endPt,
            strokeWidth = (8.5f - easeOut * 6.5f).dp.toPx().coerceAtLeast(1.5.dp.toPx()),
        )
        drawLine(
            color = IvoryHotCore.copy(alpha = 0.96f * alpha),
            start = startPt,
            end = endPt,
            strokeWidth = (2.8f - easeOut * 2.0f).dp.toPx().coerceAtLeast(0.75.dp.toPx()),
        )

        // Crisp crimson-ivory expanding frame around strike contact.
        val frameW = rw * (0.88f + easeOut * 0.44f)
        val frameH = (12f + easeOut * 22f).dp.toPx()
        drawRoundRect(
            color = CrimsonAnodizedBright.copy(alpha = 0.84f * alpha),
            topLeft = Offset(x - frameW * 0.5f, line - frameH * 0.52f),
            size = Size(frameW, frameH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = (1.6f - easeOut * 0.9f).dp.toPx().coerceAtLeast(0.6.dp.toPx())),
        )

        // 8 directional trail micro-sparks streaming along the swipe cone.
        if (motion) {
            for (k in 0 until 8) {
                val spread = ((k - 3.5f) / 3.5f) * (10f + easeOut * 16f).dp.toPx()
                val advance = (8f + easeOut * (26f + (k % 3) * 18f)).dp.toPx()
                val sx = x + dirX * advance + perpX * spread
                val sy = line + dirY * advance + perpY * spread
                val sz = (2.6f - easeOut * 1.3f).dp.toPx().coerceAtLeast(1.dp.toPx())
                val sparkColor = if (k % 2 == 0) CrimsonAnodizedBright else IvoryKeyTone
                drawCircle(
                    color = sparkColor.copy(alpha = 0.28f * alpha),
                    radius = sz * 1.9f,
                    center = Offset(sx, sy),
                )
                drawRect(
                    color = sparkColor.copy(alpha = 0.94f * alpha),
                    topLeft = Offset(sx - sz * 0.5f, sy - sz * 0.5f),
                    size = Size(sz, sz),
                )
            }
        }
        return
    }

    if (!event.kind.success) {
        // Restrained crimson signal for misses / empty taps.
        val boxW = rw * (0.76f + easeOut * 0.24f)
        val boxH = (12f + easeOut * 14f).dp.toPx()
        drawRoundRect(
            color = CrimsonAnodizedBright.copy(alpha = 0.62f * alpha),
            topLeft = Offset(x - boxW * 0.5f, line - boxH * 0.52f),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        return
    }

    // Refined monochrome + Swiss crimson layered tap impact (PERFECT vs GOOD tier).
    val isPerfect = event.grade == Judgment.PERFECT
    val coreR = rw * (if (isPerfect) (0.50f + easeOut * 0.36f) else (0.38f + easeOut * 0.24f))
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to (if (isPerfect) Color.White else IvoryKeyTone).copy(alpha = (if (isPerfect) 0.52f else 0.30f) * alpha),
            0.38f to IvoryKeyTone.copy(alpha = (if (isPerfect) 0.24f else 0.14f) * alpha),
            0.72f to CrimsonAnodizedBright.copy(alpha = (if (isPerfect) 0.12f else 0.05f) * alpha),
            1.0f to Color.Transparent,
            center = Offset(x, line),
            radius = coreR,
        ),
        radius = coreR,
        center = Offset(x, line),
    )

    // Horizontal sweep glint along the pure ivory judgment line.
    val sweepHalf = rw * (if (isPerfect) (0.48f + easeOut * 0.44f) else (0.42f + easeOut * 0.26f))
    drawRect(
        brush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.22f to IvoryKeyTone.copy(alpha = (if (isPerfect) 0.88f else 0.56f) * alpha),
            0.50f to Color.White.copy(alpha = (if (isPerfect) 0.96f else 0.66f) * alpha),
            0.78f to IvoryKeyTone.copy(alpha = (if (isPerfect) 0.88f else 0.56f) * alpha),
            1.0f to Color.Transparent,
            startX = x - sweepHalf,
            endX = x + sweepHalf,
        ),
        topLeft = Offset(x - sweepHalf, line - (if (isPerfect) 1.8.dp else 1.3.dp).toPx()),
        size = Size(sweepHalf * 2f, (if (isPerfect) 3.6.dp else 2.6.dp).toPx()),
    )

    // Inner sharp ivory Bauhaus frame.
    val innerW = rw * (0.90f + easeOut * (if (isPerfect) 0.38f else 0.24f))
    val innerH = (12f + easeOut * (if (isPerfect) 24f else 16f)).dp.toPx()
    drawRoundRect(
        color = IvoryKeyTone.copy(alpha = (if (isPerfect) 0.92f else 0.68f) * alpha),
        topLeft = Offset(x - innerW * 0.5f, line - innerH * 0.55f),
        size = Size(innerW, innerH),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        style = Stroke(width = (1.5f - easeOut * 0.85f).dp.toPx().coerceAtLeast(0.55.dp.toPx())),
    )

    // Staggered outer Swiss crimson frame exclusively for PERFECT hits.
    if (isPerfect) {
        val outerP = ((p - 0.11f) / 0.89f).coerceIn(0f, 1f)
        if (outerP > 0f) {
            val outerEase = 1f - (1f - outerP) * (1f - outerP)
            val outerAlpha = (1f - outerP) * (1f - outerP)
            val outerW = rw * (0.96f + outerEase * 0.50f)
            val outerH = (16f + outerEase * 32f).dp.toPx()
            drawRoundRect(
                color = CrimsonAnodizedBright.copy(alpha = 0.72f * outerAlpha),
                topLeft = Offset(x - outerW * 0.5f, line - outerH * 0.55f),
                size = Size(outerW, outerH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = Stroke(width = (1.25f - outerEase * 0.7f).dp.toPx().coerceAtLeast(0.5.dp.toPx())),
            )
        }
    }

    // Crisp dispersing Bauhaus micro-sparks (8 for PERFECT in ivory & crimson, 4 subtle ivory for GOOD).
    if (motion) {
        val count = if (isPerfect) 8 else 4
        for (k in 0 until count) {
            val tFrac = (k + 0.5f) / count
            val angle = -PI * (0.12 + tFrac * 0.76)
            val speedScale = if (isPerfect) (26f + (k % 3) * 12f) else (18f + (k % 2) * 8f)
            val dist = (8f + easeOut * speedScale).dp.toPx()
            val fx = x + cos(angle).toFloat() * dist * 1.18f
            val fy = line + sin(angle).toFloat() * dist
            val sz = ((if (isPerfect) 2.7f else 2.0f) - easeOut * 1.1f).dp.toPx().coerceAtLeast(1.dp.toPx())
            val sparkColor = if (isPerfect && k % 2 == 1) CrimsonAnodizedBright else IvoryKeyTone
            drawCircle(
                color = sparkColor.copy(alpha = 0.24f * alpha),
                radius = sz * 1.85f,
                center = Offset(fx, fy),
            )
            drawRect(
                color = sparkColor.copy(alpha = (if (isPerfect) 0.92f else 0.72f) * alpha),
                topLeft = Offset(fx - sz * 0.5f, fy - sz * 0.5f),
                size = Size(sz, sz),
            )
        }
    }
}
