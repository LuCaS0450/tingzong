package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

/**
 * Native overlay view mounted directly above `MapView` inside the same host container.
 * Updating it from Mapbox's camera callback avoids Compose recomposition latency so
 * the sphere lighting and ground shadow stay locked to the globe during zoom transitions.
 */
internal class GlobeLightingView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = AndroidPath()
    private var bounds: GlobeBounds? = null
    private var zoom: Double = 0.0
    private var globeZoom: Double = 0.0
    private var overviewY: Float = 0f
    private var visibleVisual: Boolean = false
    private var cachedBitmap: Bitmap? = null
    private var cachedLeft: Float = 0f
    private var cachedTop: Float = 0f
    private var cachedBounds: GlobeBounds? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateState(
        hasVisual: Boolean,
        bounds: GlobeBounds?,
        zoom: Double,
        globeZoom: Double,
        overviewY: Float,
    ) {
        val active = hasVisual && bounds != null && (zoom - globeZoom) < 0.25
        val changed = visibleVisual != active ||
            this.bounds != bounds ||
            (active && (abs(this.zoom - zoom) > 1e-4 || abs(this.globeZoom - globeZoom) > 1e-4))
        visibleVisual = active
        this.bounds = if (active) bounds else null
        this.zoom = zoom
        this.globeZoom = globeZoom
        this.overviewY = overviewY
        if (changed) invalidate()
    }

    fun release() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedBounds = null
    }

    override fun onDraw(canvas: AndroidCanvas) {
        val currentBounds = bounds ?: return
        if (!visibleVisual) return
        val deltaZoom = (zoom - globeZoom).coerceAtLeast(0.0).toFloat()
        if (deltaZoom >= 0.25f) return

        val atOverview = deltaZoom < 0.005f && abs(currentBounds.y - overviewY) <= 0.5f
        if (atOverview) {
            val bitmap = ensureOverviewCache(currentBounds)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, cachedLeft, cachedTop, null)
                return
            }
        }
        drawGlobeLighting(canvas, currentBounds, deltaZoom)
    }

    private fun ensureOverviewCache(currentBounds: GlobeBounds): Bitmap? {
        val existing = cachedBitmap
        if (existing != null && !existing.isRecycled && cachedBounds == currentBounds) {
            return existing
        }
        existing?.recycle()
        val padH = ceil(max(24f * density, currentBounds.radius * 0.24f))
        val padTop = ceil(16f * density)
        val padBottom = ceil(56f * density)
        val left = floor(currentBounds.x - currentBounds.radius - padH)
        val top = floor(currentBounds.y - currentBounds.radius - padTop)
        val width = ceil(2f * currentBounds.radius + 2f * padH + 2f).toInt().coerceAtLeast(1)
        val height = ceil(2f * currentBounds.radius + padTop + padBottom + 2f).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return null
        val offscreen = AndroidCanvas(bitmap)
        drawGlobeLighting(
            offscreen,
            currentBounds.copy(x = currentBounds.x - left, y = currentBounds.y - top),
            deltaZoom = 0f,
        )
        cachedBitmap = bitmap
        cachedLeft = left
        cachedTop = top
        cachedBounds = currentBounds
        return bitmap
    }

    private fun drawGlobeLighting(canvas: AndroidCanvas, b: GlobeBounds, deltaZoom: Float) {
        val r = (b.radius - WorldMapMath.globeClipInsetPx(density) * 0.45f).coerceAtLeast(1f)

        // 1. Two-layer floating ground shadow; fades out quickly as soon as the user zooms past overview.
        val shadowAlpha = 1f - WorldMapMath.smoothstep(0f, 0.16f, deltaZoom)
        if (shadowAlpha > 0.01f) {
            drawEllipticalShadow(
                canvas = canvas,
                cx = b.x + r * 0.12f,
                cy = b.y + r + 15f * density,
                width = r * 1.44f,
                height = 32f * density,
                colors = intArrayOf(
                    argb(0x28, 0x34, 0x38, 0.22f * shadowAlpha),
                    argb(0x28, 0x34, 0x38, 0.10f * shadowAlpha),
                    0x00000000,
                ),
                stops = floatArrayOf(0f, 0.46f, 0.82f),
            )
            drawEllipticalShadow(
                canvas = canvas,
                cx = b.x + r * 0.06f,
                cy = b.y + r + 10f * density,
                width = r * 0.82f,
                height = 13f * density,
                colors = intArrayOf(
                    argb(0x1A, 0x23, 0x26, 0.34f * shadowAlpha),
                    argb(0x1A, 0x23, 0x26, 0.15f * shadowAlpha),
                    0x00000000,
                ),
                stops = floatArrayOf(0f, 0.48f, 0.86f),
            )
        }

        // 2. Crisp haze-free spherical shading; fades out smoothly before the zoomed globe reaches screen edges.
        val lightStrength = 1f - WorldMapMath.smoothstep(0f, 0.24f, deltaZoom)
        if (lightStrength > 0.01f) {
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(b.x, b.y, r, AndroidPath.Direction.CW)
            canvas.clipPath(clipPath)

            // 2a. Compact soft silk highlight in the top-left crown.
            paint.shader = RadialGradient(
                b.x - r * 0.36f,
                b.y - r * 0.38f,
                max(1f, r * 0.36f),
                intArrayOf(
                    argb(0xFF, 0xFF, 0xFF, 0.16f * lightStrength),
                    argb(0xFF, 0xFF, 0xFF, 0.05f * lightStrength),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(b.x, b.y, r, paint)

            // 2b. Restrained bottom-right terminator shading; lit hemisphere stays 100% clear.
            paint.shader = RadialGradient(
                b.x - r * 0.34f,
                b.y - r * 0.36f,
                max(1f, r * 1.38f),
                intArrayOf(
                    0x00000000,
                    0x00000000,
                    argb(0x0A, 0x18, 0x22, 0.08f * lightStrength),
                    argb(0x07, 0x13, 0x1C, 0.18f * lightStrength),
                    argb(0x05, 0x0E, 0x16, 0.26f * lightStrength),
                ),
                floatArrayOf(0f, 0.52f, 0.72f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(b.x, b.y, r, paint)

            // 2c. Very subtle perimeter limb turn without darkening the silhouette into a black ring.
            paint.shader = RadialGradient(
                b.x,
                b.y,
                max(1f, r),
                intArrayOf(
                    0x00000000,
                    0x00000000,
                    argb(0x09, 0x17, 0x20, 0.03f * lightStrength),
                    argb(0x06, 0x11, 0x18, 0.08f * lightStrength),
                ),
                floatArrayOf(0f, 0.82f, 0.93f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(b.x, b.y, r, paint)

            paint.shader = null
            canvas.restore()
        }
    }

    private fun drawEllipticalShadow(
        canvas: AndroidCanvas,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        colors: IntArray,
        stops: FloatArray,
    ) {
        val radius = max(1f, width * 0.5f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, height / max(1f, width))
        paint.shader = RadialGradient(0f, 0f, radius, colors, stops, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun argb(r: Int, g: Int, b: Int, alpha: Float): Int =
        android.graphics.Color.argb((alpha * 255f).roundToInt().coerceIn(0, 255), r, g, b)
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
