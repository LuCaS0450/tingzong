package cn.musicpilgrimage.app.ui.room

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private val RedEnamel = intArrayOf(189, 56, 43)
private val VisitedEnamel = intArrayOf(59, 68, 63)
private val IvoryEnamel = intArrayOf(245, 242, 234)
private val IvoryEdge = intArrayOf(196, 191, 178)

@Composable
internal fun WorldGlobeLighting(controller: NativeWorldController, modifier: Modifier = Modifier) {
    Spacer(modifier.drawWithCache {
        val bounds = controller.activeGlobeBounds
        val hud = controller.hudState
        val zoom = hud.zoom
        if (!controller.hasVisual || bounds == null || zoom >= 4.0) return@drawWithCache onDrawBehind { }
        val overviewY = (size.height - WorldMapMath.baseBottomPaddingPx(size.width.toInt(), size.height.toInt())) / 2f
        if (abs(zoom - hud.globeZoom) > 0.005 || abs(bounds.y - overviewY) > 0.5f) {
            return@drawWithCache onDrawBehind { drawGlobeShadowAndRim(bounds, zoom, density) }
        }

        // The overview lighting stays fixed while the texture rotates beneath it.
        val padH = ceil(max(24f * density, bounds.radius * 0.24f))
        val padTop = ceil(16f * density)
        val padBottom = ceil(56f * density)
        val left = floor(bounds.x - bounds.radius - padH)
        val top = floor(bounds.y - bounds.radius - padTop)
        val width = ceil(2f * bounds.radius + 2f * padH + 2f).toInt()
        val height = ceil(2f * bounds.radius + padTop + padBottom + 2f).toInt()
        val lighting = ImageBitmap(width, height)
        CanvasDrawScope().draw(this, layoutDirection, GraphicsCanvas(lighting), Size(width.toFloat(), height.toFloat())) {
            drawGlobeShadowAndRim(bounds.copy(x = bounds.x - left, y = bounds.y - top), zoom, density)
        }
        onDrawBehind { drawImage(lighting, topLeft = Offset(left, top)) }
    })
}

internal fun renderWorldPinArtwork(
    density: Float,
    snapToStreet: Boolean,
    visited: Boolean = false,
    selected: Boolean = false,
    cluster: Boolean = false,
    showClusterText: Boolean = false,
): Bitmap {
    val bitmap = worldPinBitmap(density)
    val bitmapSize = bitmap.width
    val center = bitmapSize / 2f
    val zoom = if (snapToStreet) 11.0 else if (showClusterText) 3.0 else 1.0
    val pointRadius = if (snapToStreet) 6.4f else 5.0f
    val radius = if (cluster) 7.6f else if (selected) pointRadius + 2.4f else pointRadius
    val stem = if (snapToStreet) {
        if (cluster) 5.5f else if (selected) 16.5f else 11.5f
    } else {
        if (selected) 3.3f else 1.8f
    }
    val artwork = ProjectedPin(
        key = "", placeId = null, clusterId = null, name = "", pointCount = if (cluster) 2 else 0,
        visited = visited, isSelected = selected, x = center, y = center,
        hx = center, hy = center - stem * density, radius = radius, ratio = 1f, angle = 0f,
        face = 1f, stand = if (snapToStreet) 1f else 0f, opacity = 1f, light = 1f,
        highlight = 0f, lat = 0.0, lon = 0.0,
    )
    val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    CanvasDrawScope().draw(
        Density(density),
        LayoutDirection.Ltr,
        GraphicsCanvas(bitmap.asImageBitmap()),
        Size(bitmapSize.toFloat(), bitmapSize.toFloat()),
    ) {
        drawEnamelPin(artwork, zoom, density, countPaint, drawClusterCount = false)
    }
    return bitmap
}

private fun worldPinBitmap(density: Float): Bitmap {
    val size = (60f * density).roundToInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
}

internal fun renderWorldSelectionRing(density: Float): Bitmap {
    val bitmap = worldPinBitmap(density)
    val center = bitmap.width / 2f
    val canvas = android.graphics.Canvas(bitmap)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color(247, 246, 242).copy(alpha = 0.92f).toArgb()
        strokeWidth = 2.2f * density
    }
    canvas.drawCircle(center, center, 10.4f * density, ring)
    ring.color = shadedColor(RedEnamel, 1f, 0f, 0.92f).toArgb()
    ring.strokeWidth = 1.15f * density
    canvas.drawCircle(center, center, 9.1f * density, ring)
    return bitmap
}

/**
 * Map-aligned ground contact shadow projected onto the tangent plane of the 3D globe.
 * Stretches along the bottom-right direction to match the fixed top-left (315 deg) key light.
 */
internal fun renderWorldPinContactShadow(
    density: Float,
    cluster: Boolean = false,
    selected: Boolean = false,
): Bitmap {
    val bitmap = worldPinBitmap(density)
    val bitmapSize = bitmap.width
    val center = bitmapSize / 2f
    CanvasDrawScope().draw(
        Density(density),
        LayoutDirection.Ltr,
        GraphicsCanvas(bitmap.asImageBitmap()),
        Size(bitmapSize.toFloat(), bitmapSize.toFloat()),
    ) {
        val coreR = (if (selected) 5.6f else if (cluster) 5.2f else 3.8f) * density
        val castRx = (if (selected) 9.0f else if (cluster) 7.4f else 5.6f) * density
        val castRy = castRx * 0.60f
        val castOffset = (if (selected) 3.4f else if (cluster) 2.6f else 2.0f) * density

        // Directional cast shadow angled bottom-right from top-left key light.
        withTransform({
            translate(center + castOffset, center + castOffset * 1.06f)
            rotate(42f, pivot = Offset.Zero)
            scale(scaleX = 1f, scaleY = castRy / max(1f, castRx), pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF0A151B).copy(alpha = if (selected) 0.42f else 0.34f),
                    0.52f to Color(0xFF0D1B22).copy(alpha = if (selected) 0.18f else 0.14f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = castRx,
                ),
                radius = castRx,
                center = Offset.Zero,
            )
        }

        // Tighter ambient-occlusion contact pool right where the pin meets the globe surface.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF071014).copy(alpha = if (selected) 0.54f else 0.46f),
                0.55f to Color(0xFF0A161B).copy(alpha = if (selected) 0.24f else 0.18f),
                1f to Color.Transparent,
                center = Offset(center + 0.6f * density, center + 0.7f * density),
                radius = coreR,
            ),
            radius = coreR,
            center = Offset(center + 0.6f * density, center + 0.7f * density),
        )
    }
    return bitmap
}

private fun DrawScope.drawGlobeShadowAndRim(bounds: GlobeBounds, zoom: Double, density: Float) {
    // Match the rendered Mapbox globe silhouette closely so the inner Fresnel rim seals cleanly with no dark hairline seam.
    val r = (bounds.radius + 0.35f * density).coerceAtLeast(1f)
    val c = Offset(bounds.x, bounds.y)

    // 1. Two-layer floating ground shadow (top-left key light -> shadow offsets subtly to bottom-right).
    val shadowAlpha = ((3.2 - zoom) / 2.0).toFloat().coerceIn(0f, 1f)
    if (shadowAlpha > 0.01f) {
        // Soft wide penumbra.
        val penumbraW = r * 1.46f
        val penumbraH = 34f * density
        val px = bounds.x + r * 0.13f
        val py = bounds.y + r + 15f * density
        withTransform({
            translate(px, py)
            scale(scaleX = 1f, scaleY = penumbraH / max(1f, penumbraW), pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF283438).copy(alpha = 0.24f * shadowAlpha),
                    0.46f to Color(0xFF283438).copy(alpha = 0.11f * shadowAlpha),
                    0.82f to Color.Transparent,
                    center = Offset.Zero,
                    radius = penumbraW * 0.5f,
                ),
                radius = penumbraW * 0.5f,
                center = Offset.Zero,
            )
        }

        // Tighter core umbra grounding the physical sphere above the Swiss paper surface.
        val umbraW = r * 0.84f
        val umbraH = 14f * density
        val ux = bounds.x + r * 0.07f
        val uy = bounds.y + r + 10f * density
        withTransform({
            translate(ux, uy)
            scale(scaleX = 1f, scaleY = umbraH / max(1f, umbraW), pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF1A2326).copy(alpha = 0.36f * shadowAlpha),
                    0.48f to Color(0xFF1A2326).copy(alpha = 0.16f * shadowAlpha),
                    0.86f to Color.Transparent,
                    center = Offset.Zero,
                    radius = umbraW * 0.5f,
                ),
                radius = umbraW * 0.5f,
                center = Offset.Zero,
            )
        }
    }

    val lightStrength = ((4.0 - zoom) / 2.8).toFloat().coerceIn(0f, 1f)
    if (lightStrength > 0.01f) {
        // Subtle outer atmospheric softening against the warm-white paper background.
        val outerHaloR = r + 5.5f * density
        val innerStop = (r / outerHaloR).coerceIn(0.8f, 0.99f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                innerStop to Color(0xFFC5DCE6).copy(alpha = 0.14f * lightStrength),
                1f to Color.Transparent,
                center = c,
                radius = outerHaloR,
            ),
            radius = outerHaloR,
            center = c,
        )

        val spherePath = Path().apply {
            addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
        }
        clipPath(spherePath) {
            // 2a. Key light diffuse wash (top-left warm sunlit hemisphere).
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFFFFDF8).copy(alpha = 0.22f * lightStrength),
                    0.42f to Color(0xFFF6FAFC).copy(alpha = 0.10f * lightStrength),
                    0.82f to Color.Transparent,
                    center = Offset(c.x - r * 0.34f, c.y - r * 0.36f),
                    radius = r * 1.18f,
                ),
                radius = r,
                center = c,
            )

            // 2b. Soft satin ceramic specular bloom (restrained, non-plastic).
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.16f * lightStrength),
                    0.48f to Color.White.copy(alpha = 0.05f * lightStrength),
                    1f to Color.Transparent,
                    center = Offset(c.x - r * 0.37f, c.y - r * 0.39f),
                    radius = r * 0.46f,
                ),
                radius = r,
                center = c,
            )

            // 2c. Terminator and shadow hemisphere (deep slate-indigo rather than muddy black).
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.46f to Color.Transparent,
                    0.66f to Color(0xFF0E1B24).copy(alpha = 0.13f * lightStrength),
                    0.84f to Color(0xFF0A161F).copy(alpha = 0.32f * lightStrength),
                    1f to Color(0xFF071118).copy(alpha = 0.44f * lightStrength),
                    center = Offset(c.x - r * 0.32f, c.y - r * 0.34f),
                    radius = r * 1.36f,
                ),
                radius = r,
                center = c,
            )

            // 2d. Gentle limb darkening around the full perimeter for spherical wrap-around.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.74f to Color.Transparent,
                    0.90f to Color(0xFF0D1B22).copy(alpha = 0.08f * lightStrength),
                    1f to Color(0xFF09141A).copy(alpha = 0.16f * lightStrength),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )

            // 2e. Warm paper bounce light on the lower-right underside, carving out the core shadow band.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFF3EFE4).copy(alpha = 0.25f * lightStrength),
                    0.55f to Color(0xFFF0ECE0).copy(alpha = 0.10f * lightStrength),
                    1f to Color.Transparent,
                    center = Offset(c.x + r * 0.68f, c.y + r * 0.74f),
                    radius = r * 0.64f,
                ),
                radius = r,
                center = c,
            )

            // 2f. Inner Fresnel atmosphere haze softening the hard silhouette edge.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.78f to Color.Transparent,
                    0.89f to Color(0xFFCCE2EC).copy(alpha = 0.10f * lightStrength),
                    0.96f to Color(0xFFE2EFF3).copy(alpha = 0.24f * lightStrength),
                    1f to Color(0xFFF4F6F1).copy(alpha = 0.36f * lightStrength),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
        }

        // 2g. Crisp directional rim finish.
        val rimStroke = 1.0f * density
        drawCircle(
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.44f * lightStrength),
                0.50f to Color(0xFFC5DCE6).copy(alpha = 0.18f * lightStrength),
                1f to Color(0xFF233236).copy(alpha = 0.10f * lightStrength),
                start = Offset(c.x - r * 0.72f, c.y - r * 0.72f),
                end = Offset(c.x + r * 0.72f, c.y + r * 0.72f),
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
    countPaint: Paint,
    drawClusterCount: Boolean,
) {
    val opacity = pin.opacity
    val stand = pin.stand
    val isCluster = pin.pointCount > 0
    val isSelected = pin.isSelected
    val rPx = pin.radius * density
    val baseRgb = if (pin.visited) VisitedEnamel else RedEnamel

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

    // 2. Small metal washer for unselected street pins.
    if (!isSelected && stand > 0.15f) {
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
        Brush.radialGradient(
            0f to shadedColor(IvoryEnamel, pin.light * 1.04f, 38f * pin.highlight + 18f, opacity),
            0.52f to shadedColor(IvoryEnamel, pin.light, 10f * pin.highlight, opacity),
            1f to shadedColor(IvoryEdge, pin.light * 0.88f, 0f, opacity),
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
                color = shadedColor(RedEnamel, pin.light, 0f, 0.58f * opacity),
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
                color = shadedColor(RedEnamel, pin.light, 0f, coreAlpha),
                radius = coreR,
                center = headCenter,
            )
        }
        if (drawClusterCount && numOpacity > 0.04f) {
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

private fun shadedColor(rgb: IntArray, scale: Float, add: Float, alpha: Float): Color {
    val r = (rgb[0] * scale + add).roundToInt().coerceIn(0, 255)
    val g = (rgb[1] * scale + add).roundToInt().coerceIn(0, 255)
    val b = (rgb[2] * scale + add).roundToInt().coerceIn(0, 255)
    return Color(r, g, b, (alpha * 255).roundToInt().coerceIn(0, 255))
}
