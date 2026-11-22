package cn.musicpilgrimage.app.ui

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import kotlin.math.roundToInt

internal val LocalDetailZoom = staticCompositionLocalOf<DetailZoomCoordinator?> { null }

internal class DetailZoomSource(val media: String, val scale: ContentScale) {
    private var coordinates: LayoutCoordinates? = null
    var image: ImageBitmap? = null

    fun load(drawable: Drawable?) {
        image = (drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
    }

    fun position(modifier: Modifier) = modifier.onGloballyPositioned { coordinates = it }

    fun boundsIn(viewport: Rect): Rect? {
        val layout = coordinates?.takeIf { it.isAttached } ?: return null
        if (viewport.isEmpty) return null
        val bounds = layout.boundsInRoot().intersect(viewport)
        if (bounds.width < layout.size.width * .6f || bounds.height < layout.size.height * .6f) return null
        return bounds.translate(-viewport.topLeft)
    }
}

internal class DetailZoomCoordinator {
    data class SourceKey(val entryId: String, val route: String)

    data class Anchor(
        val key: SourceKey,
        val media: String,
        val image: ImageBitmap,
        val scale: ContentScale,
        val bounds: Rect,
        val viewportSize: Size,
    )

    private val sources = mutableMapOf<SourceKey, DetailZoomSource>()
    private val links = mutableMapOf<String, Anchor>()
    var viewport = Rect.Zero

    fun register(key: SourceKey, source: DetailZoomSource) { sources[key] = source }

    fun release(key: SourceKey, source: DetailZoomSource) {
        if (sources[key] === source) sources.remove(key)
    }

    fun capture(entryId: String?, route: String): Anchor? {
        val key = SourceKey(entryId ?: return null, route)
        val source = sources[key] ?: return null
        val image = source.image ?: return null
        val bounds = source.boundsIn(viewport) ?: return null
        return Anchor(key, source.media, image, source.scale, bounds, viewport.size)
    }

    fun bind(entry: NavBackStackEntry, anchor: Anchor) {
        links[entry.id] = anchor
        entry.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) links.remove(entry.id)
        })
    }

    fun thumbnailFor(entryId: String?, media: String?): ImageBitmap? {
        return links[entryId ?: return null]?.takeIf { it.media == media }?.image
    }

    fun transition(top: String, under: String, returning: Boolean, interactive: Boolean): DetailNavigationTransition {
        val anchor = links[top]?.takeIf { it.key.entryId == under && it.viewportSize == viewport.size }
        return DetailNavigationTransition(top, under, interactive) {
            if (anchor == null || !returning) anchor
            else {
                // The previous page's list has been restored and laid out by the time the
                // first moving frame is drawn. Use its live row rather than the tap position.
                val bounds = sources[anchor.key]?.takeIf { it.media == anchor.media }?.boundsIn(viewport)
                bounds?.let { anchor.copy(bounds = it) }
            }
        }
    }
}

@Composable
internal fun rememberDetailZoomSource(route: String, media: String?, scale: ContentScale): DetailZoomSource? {
    val coordinator = LocalDetailZoom.current ?: return null
    val entryId = LocalRouteEntryId.current ?: return null
    if (!LocalPhysicalMotion.current || media.isNullOrBlank()) return null
    val key = remember(entryId, route) { DetailZoomCoordinator.SourceKey(entryId, route) }
    val source = remember(key, media, scale) { DetailZoomSource(media, scale) }
    DisposableEffect(coordinator, key, source) {
        coordinator.register(key, source)
        onDispose { coordinator.release(key, source) }
    }
    return source
}

internal class DetailNavigationTransition(
    val top: String,
    val under: String,
    interactive: Boolean,
    resolveAnchor: () -> DetailZoomCoordinator.Anchor?,
) {
    val progressSpec = detailMotionSpec(interactive, visibilityThreshold = 0.002f)
    private val anchor by lazy(LazyThreadSafetyMode.NONE, resolveAnchor)

    fun foreground(openness: State<Float>): Modifier = Modifier.graphicsLayer {
        val fraction = openness.value.coerceIn(0f, 1f)
        val source = if (fraction < 1f && size.width > 0f && size.height > 0f) anchor else null
        val frame = source?.let { zoomFrame(it.bounds, size, fraction) }
        translationX = frame?.translation?.x ?: size.width * (1f - fraction)
        translationY = frame?.translation?.y ?: 0f
        scaleX = frame?.scale ?: 1f
        scaleY = scaleX
        clip = frame != null
        val radius = 64.dp.toPx() * fraction * (1f - fraction)
        shape = frame?.let { OutlineShape(it.clip, radius / it.scale) } ?: RectangleShape
    }

    fun background(openness: State<Float>): Modifier = Modifier.graphicsLayer {
        val fraction = openness.value.coerceIn(0f, 1f)
        translationX = if (fraction < 1f && anchor == null) -size.width * DetailParallaxFraction * fraction else 0f
    }

    fun foregroundDecoration(openness: State<Float>): Modifier = Modifier.graphicsLayer().drawWithCache {
        val edgeWidth = 16.dp.toPx()
        val shadow = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .14f)), -edgeWidth, 0f)
        onDrawBehind {
            val fraction = openness.value.coerceIn(0f, 1f)
            if (fraction >= 1f) return@onDrawBehind
            val source = anchor
            if (source == null) {
                drawRect(shadow, topLeft = Offset(-edgeWidth, 0f), size = Size(edgeWidth, size.height))
            } else {
                val imageAlpha = (1f - fraction / .48f).coerceAtLeast(0f)
                if (imageAlpha > 0f) {
                    val frame = zoomFrame(source.bounds, size, fraction)
                    val bitmapSize = Size(source.image.width.toFloat(), source.image.height.toFloat())
                    val factor = source.scale.computeScaleFactor(bitmapSize, frame.clip.size)
                    val width = bitmapSize.width * factor.scaleX
                    val height = bitmapSize.height * factor.scaleY
                    drawImage(
                        source.image,
                        dstOffset = IntOffset((frame.clip.center.x - width / 2f).roundToInt(), (frame.clip.center.y - height / 2f).roundToInt()),
                        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
                        alpha = imageAlpha,
                    )
                }
            }
        }
    }

    fun backgroundDecoration(openness: State<Float>): Modifier = Modifier.graphicsLayer {
        alpha = DetailScrimAlpha * openness.value.coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }.background(Color.Black)
}

private data class ZoomFrame(val translation: Offset, val scale: Float, val clip: Rect)

private fun zoomFrame(source: Rect, viewport: Size, openness: Float): ZoomFrame {
    val center = Offset(lerp(source.center.x, viewport.width / 2f, openness), lerp(source.center.y, viewport.height / 2f, openness))
    val width = lerp(source.width, viewport.width, openness)
    val height = lerp(source.height, viewport.height, openness)
    val scale = maxOf(width / viewport.width, height / viewport.height)
    val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
    val halfWidth = width / scale / 2f
    val halfHeight = height / scale / 2f
    val clip = Rect(viewportCenter.x - halfWidth, viewportCenter.y - halfHeight, viewportCenter.x + halfWidth, viewportCenter.y + halfHeight)
    return ZoomFrame(center - viewportCenter, scale, clip)
}

private data class OutlineShape(val bounds: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): Outline =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}
