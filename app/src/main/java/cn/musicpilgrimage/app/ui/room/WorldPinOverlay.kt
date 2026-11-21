package cn.musicpilgrimage.app.ui.room

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Compose 1:1 port of the enamel globe pin layer (`surface-markers.js`) and subtle sphere
 * drop-shadow/rim lighting (`focus-layer.js`). Keeps pin labels hidden on the map per `AGENTS.md`
 * while exposing full screen-reader semantics for every visible pin and cluster.
 */
@Composable
internal fun WorldPinOverlay(
    controller: NativeWorldController,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density.coerceAtLeast(1f)
    val motion = LocalPhysicalMotion.current
    val pins = controller.visiblePins
    val hud = controller.hudState
    val globeBounds = controller.activeGlobeBounds
    val frameTick = controller.frameTick
    val pulseTick = controller.selectionPulseTick

    val countPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // Touch frameTick / pulseTick to keep Canvas invalidated in lockstep with MapLibre frames.
            @Suppress("UNUSED_EXPRESSION")
            frameTick
            @Suppress("UNUSED_EXPRESSION")
            pulseTick

            if (globeBounds != null && hud.zoom < 4.0 && globeBounds.radius > 4f) {
                drawGlobeShadowAndRim(globeBounds, hud.zoom, density)
            }

            val selectionAnimProgress = controller.selectionAnimationProgress(motion)
            for (i in pins.lastIndex downTo 0) {
                drawEnamelPin(
                    pin = pins[i],
                    zoom = hud.zoom,
                    density = density,
                    selectionProgress = selectionAnimProgress,
                    motion = motion,
                    countPaint = countPaint,
                )
            }
        }

        // Accessibility-only nodes: zero pointer interception so gestures flow straight to MapView.
        for (pin in pins) {
            val touchSizeDp = 44.dp
            val halfTouchPx = with(LocalDensity.current) { (touchSizeDp / 2).toPx() }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (pin.hx - halfTouchPx).roundToInt(),
                            (pin.hy - halfTouchPx).roundToInt(),
                        )
                    }
                    .size(touchSizeDp)
                    .semantics {
                        contentDescription = pin.name
                        role = Role.Button
                        onClick(label = pin.name) {
                            controller.handlePinAccessibilityClick(pin)
                            true
                        }
                    }
            )
        }
    }
}

private fun DrawScope.drawGlobeShadowAndRim(bounds: GlobeBounds, zoom: Double, density: Float) {
    val r = (bounds.radius - WorldMapMath.globeClipInsetPx(density)).coerceAtLeast(1f)
    val shadowAlpha = ((3.2 - zoom) / 2.0).toFloat().coerceIn(0f, 1f)
    if (shadowAlpha > 0.01f) {
        val shadowW = r * 1.24f
        val shadowH = 28f * density
        val sx = bounds.x + r * 0.16f
        val sy = bounds.y + r + 14f * density
        withTransform({
            translate(sx, sy)
            scale(scaleX = 1f, scaleY = shadowH / max(1f, shadowW), pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0x6235454B).copy(alpha = 0.38f * shadowAlpha),
                    0.44f to Color(0x3035454B).copy(alpha = 0.19f * shadowAlpha),
                    0.74f to Color.Transparent,
                    center = Offset.Zero,
                    radius = shadowW * 0.5f,
                ),
                radius = shadowW * 0.5f,
                center = Offset.Zero,
            )
        }
    }

    val lightStrength = ((4.0 - zoom) / 2.8).toFloat().coerceIn(0f, 1f)
    if (lightStrength > 0.01f) {
        val c = Offset(bounds.x, bounds.y)
        // Subtle fixed top-left specular bloom and bottom-right curvature shading inside the clipped sphere.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.06f * lightStrength),
                0.55f to Color.Transparent,
                center = Offset(bounds.x - r * 0.34f, bounds.y - r * 0.38f),
                radius = r * 0.95f,
            ),
            radius = r,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                0.68f to Color.Transparent,
                1.0f to Color(0xFF1A2629).copy(alpha = 0.16f * lightStrength),
                center = Offset(bounds.x - r * 0.28f, bounds.y - r * 0.30f),
                radius = r * 1.28f,
            ),
            radius = r,
            center = c,
        )
        val rimStroke = 1.0f * density
        drawCircle(
            brush = Brush.linearGradient(
                0f to Color(0xFFA1CFE3).copy(alpha = 0.16f * lightStrength),
                1f to Color(0xFF1F2B28).copy(alpha = 0.08f * lightStrength),
                start = Offset(bounds.x - r * 0.7f, bounds.y - r * 0.7f),
                end = Offset(bounds.x + r * 0.7f, bounds.y + r * 0.7f),
            ),
            radius = max(1f, r - rimStroke * 0.5f),
            center = c,
            style = Stroke(width = rimStroke),
        )
    }
}

private fun DrawScope.drawEnamelPin(
    pin: ProjectedPin,
    zoom: Double,
    density: Float,
    selectionProgress: Float,
    motion: Boolean,
    countPaint: Paint,
) {
    val opacity = pin.opacity
    val stand = pin.stand
    val isCluster = pin.pointCount > 0
    val isSelected = pin.isSelected
    val rPx = pin.radius * density
    val baseRgb = if (pin.visited) intArrayOf(59, 68, 63) else intArrayOf(189, 56, 43)

    // 1. Ground contact shadow aligned with sphere tangent ellipse.
    val shadowOffset = stand * (if (isSelected) 3.0f else 2.0f) * density
    val shadowR = ((if (isSelected) 4.8f else if (isCluster) 3.8f else 2.9f) + stand * 1.4f) * density
    val angleDeg = Math.toDegrees(pin.angle.toDouble()).toFloat()
    withTransform({
        translate(pin.x + shadowOffset * 0.85f, pin.y + shadowOffset * 0.55f)
        rotate(angleDeg, pivot = Offset.Zero)
        scale(pin.ratio, 1f, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(20, 28, 30).copy(alpha = 0.42f * opacity),
                0.55f to Color(20, 28, 30).copy(alpha = 0.16f * opacity),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = shadowR,
            ),
            radius = shadowR,
            center = Offset.Zero,
        )
    }

    // 2. Ground anchor: double target ring + ripple for selection, metal washer in street view.
    if (isSelected) {
        val pulse = if (!motion) 1f else WorldMapMath.smoothstep(0f, 1f, selectionProgress)
        val flare = if (!motion) 0f else (1f - selectionProgress).coerceIn(0f, 1f)
        val targetRingR = (8.2f + 2.2f * stand) * density
        val ringR = WorldMapMath.mix(3.8f * density, targetRingR, pulse) + flare * 1.6f * density
        if (flare > 0.02f) {
            val rippleR = WorldMapMath.mix(ringR, ringR + 8.5f * density, 1f - flare)
            drawGroundEllipse(
                pin.x, pin.y, rippleR * pin.ratio, rippleR, angleDeg,
                shadedColor(intArrayOf(189, 56, 43), pin.light, 0f, 0.55f * flare * flare * opacity),
                strokeWidth = 1.1f * density,
            )
        }
        drawGroundEllipse(
            pin.x, pin.y, ringR * pin.ratio, ringR, angleDeg,
            Color(247, 246, 242).copy(alpha = 0.92f * opacity),
            strokeWidth = 2.2f * density,
        )
        val innerR = max(1f, ringR - 1.3f * density)
        drawGroundEllipse(
            pin.x, pin.y, innerR * pin.ratio, innerR, angleDeg,
            shadedColor(intArrayOf(189, 56, 43), pin.light, 0f, 0.92f * opacity),
            strokeWidth = 1.15f * density,
        )
    } else if (stand > 0.15f) {
        val padR = (1.5f + 0.8f * stand) * density
        withTransform({
            translate(pin.x, pin.y)
            rotate(angleDeg, pivot = Offset.Zero)
            scale(pin.ratio, 1f, pivot = Offset.Zero)
        }) {
            drawCircle(
                color = Color(36, 43, 39).copy(alpha = 0.55f * opacity * stand),
                radius = padR,
                center = Offset.Zero,
            )
        }
    }

    // 3. Steel pin stem connecting exact coordinate (x, y) to enamel bead (hx, hy).
    val dx = pin.hx - pin.x
    val dy = pin.hy - pin.y
    val stemLen = kotlin.math.hypot(dx, dy)
    if (stemLen > 1.2f * density) {
        drawLine(
            color = Color(42, 48, 45).copy(alpha = 0.88f * opacity),
            start = Offset(pin.x, pin.y),
            end = Offset(pin.hx, pin.hy),
            strokeWidth = (if (isSelected) 1.85f else 1.4f) * density,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val nx = -dy / stemLen * 0.38f * density
        val ny = dx / stemLen * 0.38f * density
        drawLine(
            color = Color.White.copy(alpha = 0.42f * opacity * pin.light),
            start = Offset(pin.x + nx, pin.y + ny),
            end = Offset(pin.hx + nx, pin.hy + ny),
            strokeWidth = 0.55f * density,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }

    // 4. Spherical enamel bead head.
    val headCenter = Offset(pin.hx, pin.hy)
    drawCircle(
        color = Color(24, 30, 27).copy(alpha = 0.22f * opacity),
        radius = rPx + 0.4f * density,
        center = Offset(pin.hx + 0.5f * density, pin.hy + 1.1f * density),
    )

    val gradCenter = Offset(pin.hx - rPx * 0.36f, pin.hy - rPx * 0.40f)
    val beadBrush = if (isCluster) {
        val ivory = intArrayOf(245, 242, 234)
        val ivoryEdge = intArrayOf(196, 191, 178)
        Brush.radialGradient(
            0f to shadedColor(ivory, pin.light * 1.04f, 38f * pin.highlight + 18f, opacity),
            0.52f to shadedColor(ivory, pin.light, 10f * pin.highlight, opacity),
            1f to shadedColor(ivoryEdge, pin.light * 0.88f, 0f, opacity),
            center = gradCenter,
            radius = rPx * 1.15f,
        )
    } else {
        Brush.radialGradient(
            0f to shadedColor(baseRgb, pin.light * 1.12f, 72f * pin.light + 40f * pin.highlight, opacity),
            0.46f to shadedColor(baseRgb, pin.light, 18f * pin.highlight, opacity),
            1f to shadedColor(baseRgb, pin.light * 0.56f, 0f, opacity),
            center = gradCenter,
            radius = rPx * 1.15f,
        )
    }
    drawCircle(brush = beadBrush, radius = rPx, center = headCenter)

    when {
        isSelected -> {
            drawCircle(
                color = Color(247, 246, 242).copy(alpha = 0.96f * opacity),
                radius = rPx,
                center = headCenter,
                style = Stroke(1.65f * density),
            )
            drawCircle(
                color = Color(36, 43, 39).copy(alpha = 0.38f * opacity),
                radius = rPx + 1.1f * density,
                center = headCenter,
                style = Stroke(0.85f * density),
            )
        }
        isCluster -> {
            drawCircle(
                color = Color(36, 43, 39).copy(alpha = 0.46f * opacity),
                radius = rPx,
                center = headCenter,
                style = Stroke(1.15f * density),
            )
            val innerClusterR = max(1.5f * density, rPx - 2.1f * density)
            drawCircle(
                color = shadedColor(intArrayOf(189, 56, 43), pin.light, 0f, 0.58f * opacity),
                radius = innerClusterR,
                center = headCenter,
                style = Stroke(0.85f * density),
            )
        }
        else -> {
            drawCircle(
                color = Color(247, 246, 242).copy(alpha = 0.82f * opacity),
                radius = rPx,
                center = headCenter,
                style = Stroke(0.95f * density),
            )
            drawCircle(
                color = Color(24, 30, 27).copy(alpha = 0.28f * opacity),
                radius = rPx + 0.55f * density,
                center = headCenter,
                style = Stroke(0.55f * density),
            )
        }
    }

    // Specular enamel highlight dot.
    withTransform({
        translate(pin.hx - rPx * 0.33f, pin.hy - rPx * 0.37f)
        rotate(-39f, pivot = Offset.Zero)
    }) {
        val hw = rPx * 0.27f
        val hh = rPx * 0.16f
        drawOval(
            color = Color.White.copy(
                alpha = (if (isCluster) 0.68f else 0.54f) * opacity * (pin.light + 0.15f).coerceIn(0.5f, 1.1f)
            ),
            topLeft = Offset(-hw, -hh),
            size = Size(hw * 2f, hh * 2f),
        )
    }

    // 5. Cluster vermilion core (low zoom / near limb) transitioning to count numeral.
    if (isCluster) {
        val zoomShow = WorldMapMath.smoothstep(1.65f, 2.55f, zoom.toFloat())
        val limbShow = WorldMapMath.smoothstep(0.28f, 0.52f, pin.face)
        val numOpacity = zoomShow * limbShow
        if (numOpacity < 0.98f) {
            val coreAlpha = opacity * (1f - numOpacity * 0.92f)
            val coreR = WorldMapMath.mix(rPx * 0.36f, rPx * 0.22f, numOpacity)
            drawCircle(
                color = shadedColor(intArrayOf(189, 56, 43), pin.light, 0f, coreAlpha),
                radius = coreR,
                center = headCenter,
            )
        }
        if (numOpacity > 0.04f) {
            val fontScale = when {
                pin.pointCount >= 100 -> 0.78f
                pin.pointCount >= 10 -> 0.88f
                else -> 0.96f
            }
            countPaint.textSize = rPx * fontScale
            countPaint.color = android.graphics.Color.argb(
                (opacity * numOpacity * 255).roundToInt().coerceIn(0, 255),
                36,
                43,
                39,
            )
            val baseline = pin.hy - (countPaint.descent() + countPaint.ascent()) / 2f
            drawContext.canvas.nativeCanvas.drawText(pin.pointCount.toString(), pin.hx, baseline, countPaint)
        }
    }
}

private fun DrawScope.drawGroundEllipse(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    angleDeg: Float,
    color: Color,
    strokeWidth: Float,
) {
    withTransform({
        translate(cx, cy)
        rotate(angleDeg, pivot = Offset.Zero)
    }) {
        drawOval(
            color = color,
            topLeft = Offset(-rx, -ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(strokeWidth),
        )
    }
}

private fun shadedColor(rgb: IntArray, scale: Float, add: Float, alpha: Float): Color {
    val r = (rgb[0] * scale + add).roundToInt().coerceIn(0, 255)
    val g = (rgb[1] * scale + add).roundToInt().coerceIn(0, 255)
    val b = (rgb[2] * scale + add).roundToInt().coerceIn(0, 255)
    return Color(r, g, b, (alpha * 255).roundToInt().coerceIn(0, 255))
}
