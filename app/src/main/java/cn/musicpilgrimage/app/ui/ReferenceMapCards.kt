package cn.musicpilgrimage.app.ui

import android.animation.ValueAnimator
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.displayScene
import coil.compose.SubcomposeAsyncImage
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val ReferenceTipPosition = SemanticsPropertyKey<Offset>("ReferenceTipPosition")
private var SemanticsPropertyReceiver.referenceTipPosition by ReferenceTipPosition

/** Body and pointed end share one outline and one shadow, including edge positions. */
private class ReferenceBubbleShape(private val bubble: MapReferenceBubble, private val pixelsPerDp: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val body = bubble.body
        val left = body.left - bubble.touch.left
        val top = body.top - bubble.touch.top
        val right = left + body.width
        val bottom = top + body.height
        val tipX = bubble.tipX - bubble.touch.left
        val tipY = bubble.tipY - bubble.touch.top
        val radius = if (bubble.kind == MapReferenceKind.Cover) 5 * pixelsPerDp else min(body.width, body.height) / 2
        val half = (if (bubble.kind == MapReferenceKind.Cover) 6 else 5) * pixelsPerDp
        return Outline.Generic(Path().apply {
            val step=2*pixelsPerDp
            moveTo(left+step,top);lineTo(right-step,top);lineTo(right-step,top+step)
            lineTo(right,top+step);lineTo(right,bottom-step);lineTo(right-step,bottom-step)
            lineTo(right-step,bottom);lineTo(left+step,bottom);lineTo(left+step,bottom-step)
            lineTo(left,bottom-step);lineTo(left,top+step);lineTo(left+step,top+step);close()
            moveTo(tipX - half, bottom - 2 * pixelsPerDp)
            lineTo(tipX-half,bottom+step);lineTo(tipX-step,bottom+step)
            lineTo(tipX-step,tipY);lineTo(tipX+step,tipY);lineTo(tipX+step,bottom+step)
            lineTo(tipX+half,bottom+step);lineTo(tipX + half, bottom - 2 * pixelsPerDp)
            close()
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceMapCards(
    mapView: MapView, map: MapLibreMap, places: List<Place>,
    onPlace: (Place) -> Unit
) {
    val density = LocalDensity.current
    val references = remember(places) { places.distinctBy { it.id }.filter { it.displayScene() != null && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 } }
    val byId = remember(references) { references.associateBy { it.id } }
    val currentOnPlace by rememberUpdatedState(onPlace)
    val motion by rememberUpdatedState(LocalPhysicalMotion.current)
    var bubbles by remember(map, references) { mutableStateOf(emptyList<MapReferenceBubble>()) }
    var expandCluster by remember(map) { mutableStateOf<((MapReferenceBubble) -> Unit)?>(null) }
    var sheetPlaces by remember(map) { mutableStateOf<List<Place>?>(null) }
    LaunchedEffect(references) { sheetPlaces = sheetPlaces?.mapNotNull { byId[it.id] }?.takeIf { it.isNotEmpty() } }

    DisposableEffect(mapView, map, references, density.density, density.fontScale) {
        var disposed = false
        var pendingExpansion: Set<String>? = null
        fun updatePositions() {
            if (disposed || mapView.width == 0 || mapView.height == 0) return
            val anchors = references.map { place ->
                val point = map.projection.toScreenLocation(LatLng(place.latitude, place.longitude))
                MapReferenceAnchor(place.id, point.x, point.y)
            }
            bubbles = layoutMapReferences(anchors, mapView.width.toFloat(), mapView.height.toFloat(),
                density.density, map.cameraPosition.zoom, density.fontScale)
        }
        fun finishExpansion() {
            if (disposed) return
            updatePositions()
            val ids = pendingExpansion ?: return
            pendingExpansion = null
            // A fully unresolved group (including coincident coordinates) gets an accessible list.
            if (bubbles.any { bubble -> bubble.anchors.size > 1 && bubble.anchors.map { it.id }.containsAll(ids) }) {
                sheetPlaces = references.filter { it.id in ids }
            }
        }
        expandCluster = expand@{ bubble ->
            if (disposed) return@expand
            val members = bubble.anchors.mapNotNull { byId[it.id] }
            val limit = min(18.0, map.maxZoomLevel)
            if (map.cameraPosition.zoom >= limit - .1) { sheetPlaces = members; return@expand }
            pendingExpansion = members.map { it.id }.toSet()
            val coordinates = members.map { LatLng(it.latitude, it.longitude) }
            val spread = coordinates.maxOf { it.distanceTo(coordinates.first()) }
            val padding = min(80 * density.density, min(mapView.width, mapView.height) / 4f).roundToInt()
            val fitted = if (spread >= 1) map.getCameraForLatLngBounds(LatLngBounds.Builder().includes(coordinates).build(), intArrayOf(padding, padding, padding, padding)) else null
            val center = fitted?.target ?: map.projection.fromScreenLocation(PointF(bubble.tipX, bubble.tipY))
            val fittedZoom = fitted?.zoom?.takeIf { it.isFinite() } ?: (map.cameraPosition.zoom + 2)
            val zoom = max(map.cameraPosition.zoom + 1, fittedZoom).coerceAtMost(limit)
            val update = CameraUpdateFactory.newLatLngZoom(center, zoom)
            if (motion && ValueAnimator.areAnimatorsEnabled()) {
                map.animateCamera(update, 400, object : MapLibreMap.CancelableCallback {
                    override fun onFinish() { finishExpansion() }
                    override fun onCancel() { pendingExpansion = null }
                })
            } else {
                map.moveCamera(update)
                mapView.post { finishExpansion() }
            }
        }
        val move = MapLibreMap.OnCameraMoveListener { updatePositions() }
        val idle = MapLibreMap.OnCameraIdleListener { updatePositions() }
        val frame = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> updatePositions() }
        val layout = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePositions() }
        map.addOnCameraMoveListener(move); map.addOnCameraIdleListener(idle)
        mapView.addOnDidFinishRenderingFrameListener(frame); mapView.addOnLayoutChangeListener(layout)
        mapView.post { updatePositions() }
        onDispose {
            disposed = true
            expandCluster = null
            map.removeOnCameraMoveListener(move); map.removeOnCameraIdleListener(idle)
            mapView.removeOnDidFinishRenderingFrameListener(frame); mapView.removeOnLayoutChangeListener(layout)
        }
    }

    bubbles.forEach { bubble ->
        key(bubble.key, bubble.kind) {
            val place = requireNotNull(byId[bubble.anchors.first().id])
            val scene = requireNotNull(place.displayScene())
            val cover = requireNotNull(scene.displayCover)
            var imageStatus by remember(cover.thumbnailUrl, cover.imageUrl) { mutableStateOf("图片加载中") }
            var imageRetry by remember(cover.id, cover.imageUrl) { mutableIntStateOf(0) }
            val shape = remember(bubble, density.density) { ReferenceBubbleShape(bubble, density.density) }
            val tag = when (bubble.kind) {
                MapReferenceKind.Cover -> "map-reference-" + place.id
                MapReferenceKind.Point -> "map-reference-marker-" + place.id
                MapReferenceKind.Cluster -> "map-reference-cluster-" + bubble.key
            }
            Box(Modifier.absoluteOffset { IntOffset(bubble.touch.left.roundToInt(), bubble.touch.top.roundToInt()) }
                .size((bubble.touch.width / density.density).dp, (bubble.touch.height / density.density).dp)
                .testTag(tag)
                .semantics(mergeDescendants = true) {
                    referenceTipPosition = Offset(bubble.tipX - bubble.touch.left, bubble.tipY - bubble.touch.top)
                    contentDescription = if (bubble.kind == MapReferenceKind.Cluster) bubble.anchors.size.toString() + "个参考地点，点击放大查看"
                        else place.city + "，" + scene.work + "发行封面卡片"
                    stateDescription = when (bubble.kind) {
                        MapReferenceKind.Cover -> imageStatus
                        MapReferenceKind.Point -> "放大地图后显示封面"
                        MapReferenceKind.Cluster -> "合并的地点"
                    }
                }
                .shadow(if (bubble.kind == MapReferenceKind.Cover) 4.dp else 2.dp, shape).clip(shape)
                .background(if (bubble.kind == MapReferenceKind.Point) Acid else Panel)
                .clickable(role = Role.Button, onClickLabel = if (bubble.kind == MapReferenceKind.Cluster) "展开附近地点" else "查看地点详情") {
                    if (bubble.kind == MapReferenceKind.Cluster) expandCluster?.invoke(bubble) else currentOnPlace(place)
                }) {
                val bodyModifier = Modifier.absoluteOffset {
                    IntOffset((bubble.body.left - bubble.touch.left).roundToInt(), (bubble.body.top - bubble.touch.top).roundToInt())
                }.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size((bubble.body.width / density.density).dp, (bubble.body.height / density.density).dp)
                when (bubble.kind) {
                    MapReferenceKind.Cover -> key(cover.id, imageRetry) { SubcomposeAsyncImage(cover.thumbnailUrl ?: cover.imageUrl, contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onSuccess = { imageStatus = "图片已加载" }, onError = { imageStatus = "图片加载失败" },
                        modifier = bodyModifier.padding(4.dp).clip(RoundedCornerShape(2.dp)),
                        loading = { Box(Modifier.background(Panel),contentAlignment=Alignment.Center) {Text("加载中",fontSize=9.sp,color=Muted)} },
                        error = { Box(Modifier.fillMaxSize().clickable(role=Role.Button,onClickLabel="重试发行封面") {
                            imageStatus="图片加载中";imageRetry++
                        },contentAlignment=Alignment.Center) {Text("重试",fontSize=11.sp,color=Acid)} }) }
                    MapReferenceKind.Point -> Box(bodyModifier, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, Modifier.size(14.dp), tint = Color.White)
                    }
                    MapReferenceKind.Cluster -> Box(bodyModifier, contentAlignment = Alignment.Center) {
                        Text(bubble.anchors.size.toString(), color = Acid, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clearAndSetSemantics {})
                    }
                }
            }
        }
    }
    sheetPlaces?.let { members ->
        ModalBottomSheet(onDismissRequest = { sheetPlaces = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("附近 " + members.size + " 个参考地点", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).testTag("map-reference-list").semantics { heading() })
                    TextButton({ sheetPlaces = null }) { Text("关闭") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(members, key = { it.id }) { member ->
                        PlaceRow(member, onClick = { sheetPlaces = null; currentOnPlace(member) })
                    }
                }
            }
        }
    }
}
