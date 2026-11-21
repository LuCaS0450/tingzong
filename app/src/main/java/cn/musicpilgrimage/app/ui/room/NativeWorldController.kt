package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.canonicalRegionName
import cn.musicpilgrimage.app.data.unifiedDestinationRegion
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Projection
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal const val InitialWorldLongitude = 35.0
internal const val InitialWorldLatitude = 24.0
internal const val WorldFovDegrees = MapLibreConstants.DEFAULT_FOV

internal data class GlobeBounds(val x: Float, val y: Float, val radius: Float)

internal data class ProjectedPin(
    val key: String,
    val placeId: String?,
    val clusterId: Long?,
    val name: String,
    val pointCount: Int,
    val visited: Boolean,
    val isSelected: Boolean,
    val x: Float,
    val y: Float,
    val hx: Float,
    val hy: Float,
    val radius: Float,
    val ratio: Float,
    val angle: Float,
    val face: Float,
    val stand: Float,
    val opacity: Float,
    val light: Float,
    val highlight: Float,
    val lat: Double,
    val lon: Double,
)

internal data class WorldHudState(
    val longitude: Double = InitialWorldLongitude,
    val latitude: Double = InitialWorldLatitude,
    val zoom: Double = 0.9,
    val visualZoom: Double = 0.9,
    val globeZoom: Double = 0.9,
    val compactHeader: Boolean = false,
    val enlarged: Boolean = false,
    val beyondGlobe: Boolean = false,
    val satelliteScale: Boolean = true,
    val levelLabel: String = "地球",
    val regionName: String = "",
    val cityName: String = "",
    val scaleLabel: String = "",
    val scaleWidthPx: Float = 0f,
    val loadingVisible: Boolean = false,
    val progressVisible: Boolean = false,
    val loadingError: Boolean = false,
    val loadingText: String = "正在加载街道",
)

internal data class WorldDiagnosticsSnapshot(
    val ready: Boolean,
    val hasVisual: Boolean,
    val moving: Boolean,
    val longitude: Double,
    val latitude: Double,
    val zoom: Double,
    val selected: String,
    val summaryOpen: Boolean,
    val placesCount: Int,
    val visiblePinsCount: Int,
    val globeness: Float,
)

internal sealed interface WorldTapEvent {
    data class SelectPlace(val placeId: String) : WorldTapEvent
    data class ChoosePlaces(val placeIds: List<String>) : WorldTapEvent
    data class DismissSummary(val placeId: String) : WorldTapEvent
}

internal object WorldMapMath {
    fun wrapLongitude(lon: Double): Double {
        if (!lon.isFinite()) return 0.0
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    fun clampLatitude(lat: Double): Double =
        if (!lat.isFinite()) 0.0 else lat.coerceIn(-80.0, 80.0)

    fun smoothstep(a: Float, b: Float, value: Float): Float {
        val t = ((value - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun equatorialZoom(zoom: Double, latitude: Double): Double =
        zoom - log2(max(0.087, cos(Math.toRadians(latitude))))

    fun zoomForEquatorialZoom(eqZoom: Double, latitude: Double): Double =
        eqZoom + log2(max(0.087, cos(Math.toRadians(latitude))))

    fun visualZoom(zoom: Double, latitude: Double): Double =
        zoom - globenessForZoom(zoom).toDouble() * log2(max(0.087, cos(Math.toRadians(latitude))))

    fun globeEquatorialZoom(
        stageWidthDp: Double,
        stageHeightDp: Double,
        fovDegrees: Double = WorldFovDegrees,
    ): Double {
        if (stageWidthDp <= 0.0 || stageHeightDp <= 0.0) return 0.9
        val r = stageWidthDp * (304.0 / 360.0) / 2.0
        val d = stageHeightDp / (2.0 * tan(Math.toRadians(fovDegrees / 2.0)).coerceAtLeast(1e-4))
        val bigR = r * r / d + r * sqrt(1.0 + r * r / (d * d))
        return log2(bigR * 2.0 * PI / 512.0)
    }

    fun globeZoom(
        latitude: Double,
        stageWidthDp: Double,
        stageHeightDp: Double,
        fovDegrees: Double = WorldFovDegrees,
    ): Double {
        if (stageWidthDp <= 0.0 || stageHeightDp <= 0.0) return 0.9
        val eqZoom = globeEquatorialZoom(stageWidthDp, stageHeightDp, fovDegrees)
        return zoomForEquatorialZoom(eqZoom, latitude).coerceIn(0.0, 3.0)
    }

    fun maxConstantSizeGlobeLatitude(eqZoom: Double): Double {
        val minCos = 2.0.pow(-eqZoom).coerceIn(0.087, 1.0)
        return min(80.0, Math.toDegrees(acos(minCos)))
    }

    /**
     * Infers the rendered sphere screen radius `r = R / sqrt(1 + 2 * R / d)` from two
     * meridional screen offsets `(s1, s2)` at great-circle angular separations `(theta1Rad, theta2Rad)`.
     */
    fun measureGlobeRadiusFromProjection(
        s1: Double,
        theta1Rad: Double,
        s2: Double,
        theta2Rad: Double,
    ): Double? {
        if (!s1.isFinite() || !s2.isFinite() || s1 <= 1.0 || s2 <= s1) return null
        val sin1 = sin(theta1Rad)
        val sin2 = sin(theta2Rad)
        val c1 = 1.0 - cos(theta1Rad)
        val c2 = 1.0 - cos(theta2Rad)
        val denom = s2 * sin1 * c2 - s1 * sin2 * c1
        if (abs(denom) < 1e-6) return null
        val k = (s1 * sin2 - s2 * sin1) / denom
        if (!k.isFinite() || k <= 1e-6 || k >= 1.5) return null
        val bigR = s1 * (1.0 + k * c1) / sin1
        if (!bigR.isFinite() || bigR <= 1.0) return null
        val radius = bigR / sqrt(1.0 + 2.0 * k)
        return radius.takeIf { it.isFinite() && it > 1.0 }
    }

    fun globeClipInsetPx(density: Float): Float = max(1.25f, 0.75f * density)

    fun globeBounds(
        zoom: Double,
        latitude: Double,
        centerX: Float,
        centerY: Float,
        stageHeightPx: Float,
        density: Float,
        fovDegrees: Double = WorldFovDegrees,
    ): GlobeBounds {
        val worldSizePx = 512.0 * 2.0.pow(zoom) * density.coerceAtLeast(1f)
        val bigR = worldSizePx / (2.0 * PI * max(0.087, cos(Math.toRadians(latitude))))
        val d = stageHeightPx.toDouble().coerceAtLeast(1.0) /
            (2.0 * tan(Math.toRadians(fovDegrees / 2.0)).coerceAtLeast(1e-4))
        val radiusPx = (bigR / sqrt(1.0 + 2.0 * bigR / d)).toFloat()
        return GlobeBounds(centerX, centerY, radiusPx)
    }

    fun baseBottomPaddingPx(stageWidthPx: Int, stageHeightPx: Int): Int {
        if (stageWidthPx <= 0 || stageHeightPx <= 0) return 0
        val unit = stageWidthPx / 360f
        val idealCenterFromStageTop = stageWidthPx * (352f / 360f) - 64f * unit
        return max(0f, stageHeightPx - 2f * idealCenterFromStageTop).toInt()
    }

    fun scaleBarMetric(zoom: Double, latitude: Double, density: Float): Pair<String, Float> {
        val safeDensity = density.coerceAtLeast(1f)
        val metersPerPx =
            40075016.686 * max(0.087, cos(Math.toRadians(latitude))) / (512.0 * 2.0.pow(zoom) * safeDensity)
        val idealMeters = (metersPerPx * 80.0 * safeDensity).coerceAtLeast(1.0)
        val power = 10.0.pow(floor(log10(idealMeters)))
        val value = when {
            idealMeters >= 10.0 * power -> 10.0 * power
            idealMeters >= 5.0 * power -> 5.0 * power
            idealMeters >= 2.0 * power -> 2.0 * power
            else -> power
        }
        val intValue = value.toInt().coerceAtLeast(1)
        val label = if (intValue >= 1000) "${intValue / 1000} km" else "$intValue m"
        val barPx = (value / metersPerPx).toFloat().coerceIn(24f, 140f * safeDensity)
        return label to barPx
    }

    fun levelLabel(zoom: Double): String = when {
        zoom >= 13.0 -> "街道"
        zoom >= 7.0 -> "城市"
        zoom >= 3.0 -> "国家或地区"
        else -> "地球"
    }

    fun zoomRailFraction(zoom: Double, globeZoom: Double): Float = when {
        zoom >= 11.0 -> ((15.0 - zoom) / 4.0 / 3.0).coerceAtLeast(0.0)
        zoom >= 5.0 -> (1.0 + (11.0 - zoom) / 6.0) / 3.0
        else -> (2.0 + ((5.0 - zoom) / (5.0 - globeZoom)).coerceIn(0.0, 1.0)) / 3.0
    }.toFloat()

    fun globenessForZoom(zoom: Double): Float = ((6.0 - zoom) / 1.0).toFloat().coerceIn(0f, 1f)

    fun placeRadiusDp(zoom: Double): Float = 5.0f + 2.8f * smoothstep(2f, 14f, zoom.toFloat())
}

internal object WorldTestRegistry {
    @Volatile
    var activeController: NativeWorldController? = null
}

@Stable
internal class NativeWorldController(
    context: Context,
    private val browse: RoomBrowseState,
    private val initialSnapshot: WorldCameraSnapshot? = browse.worldState,
) {
    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var countries: List<CountryPolygon> = emptyList()
    private var pinsDirty: Boolean = true

    var ready by mutableStateOf(false)
        private set
    var hasVisual by mutableStateOf(false)
        private set
    var isMoving by mutableStateOf(false)
        private set
    var selectionPulseTick by mutableIntStateOf(0)
        private set
    private var pinCamera: CameraPosition? = null
    var pinZoom by mutableStateOf(initialSnapshot?.zoom ?: 0.9)
        private set
    var hudState by mutableStateOf(
        run {
            val initLat = initialSnapshot?.latitude ?: InitialWorldLatitude
            val initZoom = initialSnapshot?.zoom ?: 0.9
            val initVisualZoom = WorldMapMath.visualZoom(initZoom, initLat)
            WorldHudState(
                longitude = initialSnapshot?.longitude ?: InitialWorldLongitude,
                latitude = initLat,
                zoom = initZoom,
                visualZoom = initVisualZoom,
                compactHeader = initVisualZoom >= 3.0,
                enlarged = initZoom > 0.91,
                beyondGlobe = initVisualZoom >= 4.0,
                satelliteScale = initZoom < 8.0,
                levelLabel = WorldMapMath.levelLabel(initVisualZoom),
            )
        }
    )
        private set

    var visiblePins by mutableStateOf<List<ProjectedPin>>(emptyList())
        private set
    var activeGlobeBounds by mutableStateOf<GlobeBounds?>(null)
        private set
    var currentGlobeness by mutableStateOf(WorldMapMath.globenessForZoom(initialSnapshot?.zoom ?: 0.9))
        private set

    private var entries: List<WorldPlaceEntry> = emptyList()
    private var byId: Map<String, WorldPlaceEntry> = emptyMap()
    private var visits: Set<String> = emptySet()
    private var selectedId: String = initialSnapshot?.selected.orEmpty()
    private var summaryOpen: Boolean = initialSnapshot?.summaryOpen == true
    private var summaryHeightPx: Int = 0
    private var motionEnabled: Boolean = true
    private var pendingFocus: WorldFocus? = null
    private var lastAppliedFocusToken: Long = 0L

    private var stageWidthPx: Int = 0
    private var stageHeightPx: Int = 0
    private var lastSyncedEntries: List<WorldPlaceEntry>? = null
    private var lastSyncedVisits: Set<String>? = null
    private var lastSyncedSelectedId: String? = null
    private var initialCameraPlaced = false
    private var destroyed = false
    private var lastSelectedKey: String? = null
    private var tileLoadingSinceMs: Long = 0L
    private var lastRetryAtMs: Long = 0L
    private data class TileRequest(val source: String, val z: Int, val x: Int, val y: Int)
    private val activeTileRequests = mutableSetOf<TileRequest>()
    private var cameraTargetSeq: Long = 0L
    private var activeCameraTargetSeq: Long = 0L
    private var activeTargetCamera: CameraPosition? = null

    private enum class TouchMode { NONE, DRAG, PINCH }

    private var touchMode: TouchMode = TouchMode.NONE
    private var gestureAnchor: LatLng? = null
    private var gestureDownX: Float = 0f
    private var gestureDownY: Float = 0f
    private var gestureDownTimeMs: Long = 0L
    private var gestureMoved: Boolean = false
    private var gestureStartLat: Double = InitialWorldLatitude
    private var gestureStartZoom: Double = 0.9
    private var gestureStartEqZoom: Double = 0.9
    private var pinchStartSpan: Float = 0f
    private var pinchStartZoom: Double = 0.0
    private var pinchMaxMove: Float = 0f
    private var lastTapTimeMs: Long = 0L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f
    private var solvingCamera: Boolean = false
    private var clipGlobeActive: Boolean = false
    private var clipGlobeX: Float = 0f
    private var clipGlobeY: Float = 0f
    private var clipGlobeR: Float = 0f

    var onTapEvent: ((WorldTapEvent) -> Unit)? = null

    private val loadingTicker = object : Runnable {
        override fun run() {
            if (destroyed) return
            updateLoadingState()
            mainHandler.postDelayed(this, 180L)
        }
    }

    init {
        WorldTilePipeline.configureMapLibre(appContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .translucentTextureSurface(true)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .foregroundLoadColor(Color.TRANSPARENT)
            .pixelRatio(density)
        mapView = MapView(context, options).apply {
            setBackgroundColor(Color.TRANSPARENT)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (clipGlobeActive && clipGlobeR > 1f) {
                        val r = clipGlobeR
                        outline.setRoundRect(
                            (clipGlobeX - r).roundToInt(),
                            (clipGlobeY - r).roundToInt(),
                            (clipGlobeX + r).roundToInt(),
                            (clipGlobeY + r).roundToInt(),
                            r,
                        )
                    } else {
                        outline.setRect(0, 0, view.width, view.height)
                    }
                }
            }
            clipToOutline = false
            setOnTouchListener { _, event -> handleTouchEvent(event) }
        }
        mapView.onCreate(null)

        scope.launch {
            countries = withContext(Dispatchers.Default) { WorldCountryLookup.load(appContext) }
            updateRegionAndCityLabels()
        }

        mapView.addOnSourceChangedListener { source ->
            if (!destroyed && source == "places") pinsDirty = true
        }
        // The SDK refreshes its camera cache in its own stats listener. Use the
        // same listener list so projection runs after that refresh.
        mapView.addOnDidFinishRenderingFrameListener { _, _ ->
            if (destroyed || !ready || !initialCameraPlaced || solvingCamera) {
                return@addOnDidFinishRenderingFrameListener
            }
            val camera = map?.cameraPosition ?: return@addOnDidFinishRenderingFrameListener
            if (pinsDirty || camera != pinCamera) {
                pinsDirty = false
                pinCamera = camera
                recomputePins(camera)
                syncHudFromCamera()
            }
            if (!hasVisual) {
                hasVisual = true
                flushPendingFocus()
            }
        }
        mapView.addOnDidFailLoadingMapListener {
            if (!destroyed) updateLoadingState()
        }
        mapView.addOnDidFinishRenderingMapListener { fully ->
            if (!destroyed && fully) {
                activeTileRequests.clear()
                tileLoadingSinceMs = 0L
                updateLoadingState()
            }
        }
        mapView.addOnDidBecomeIdleListener {
            if (!destroyed) {
                activeTileRequests.clear()
                tileLoadingSinceMs = 0L
                updateLoadingState()
            }
        }
        mapView.addOnTileActionListener { operation, x, y, z, _, _, source ->
            if (destroyed) return@addOnTileActionListener
            if (source == "places" && operation == TileOperation.EndParse) pinsDirty = true
            if (source != "earth-hd" && source != "streets") return@addOnTileActionListener
            when (operation) {
                TileOperation.RequestedFromNetwork -> {
                    activeTileRequests.add(TileRequest(source, z, x, y))
                    if (tileLoadingSinceMs == 0L) tileLoadingSinceMs = System.currentTimeMillis()
                }
                TileOperation.LoadFromNetwork,
                TileOperation.LoadFromCache,
                TileOperation.Error,
                TileOperation.Cancelled -> {
                    activeTileRequests.remove(TileRequest(source, z, x, y))
                    if (activeTileRequests.isEmpty()) {
                        tileLoadingSinceMs = 0L
                    }
                }
                else -> Unit
            }
        }

        mapView.getMapAsync { libreMap ->
            if (destroyed) return@getMapAsync
            map = libreMap
            configureMap(libreMap)
        }
    }

    private fun configureMap(libreMap: MapLibreMap) {
        libreMap.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isCompassEnabled = false
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isScrollGesturesEnabled = false
            isZoomGesturesEnabled = false
            isFlingVelocityAnimationEnabled = false
            isRotateVelocityAnimationEnabled = false
            isScaleVelocityAnimationEnabled = false
            isDoubleTapGesturesEnabled = false
            isQuickZoomGesturesEnabled = false
        }
        libreMap.setMinZoomPreference(0.0)
        libreMap.setMaxZoomPreference(18.0)

        libreMap.addOnCameraMoveStartedListener { reason ->
            if (destroyed || solvingCamera) return@addOnCameraMoveStartedListener
            isMoving = true
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                activeCameraTargetSeq = 0L
                activeTargetCamera = null
            }
        }
        libreMap.addOnCameraIdleListener {
            if (destroyed || solvingCamera || touchMode != TouchMode.NONE) return@addOnCameraIdleListener
            isMoving = false
            pinsDirty = true
            clampCameraBoundsOnIdle()
            syncHudFromCamera()
            updateRegionAndCityLabels()
            persistCameraSnapshot()
            updateLoadingState()
        }

        val styleJson = WorldTilePipeline.buildWorldStyleJson()
        libreMap.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
            if (destroyed) return@setStyle
            style = loadedStyle
            configureStyleSources(loadedStyle)
            applyInitialCameraIfNeeded()
            syncGeoJsonSources(force = true)
            ready = true
            syncHudFromCamera()
            updateRegionAndCityLabels()
            flushPendingFocus()
        }
    }

    private fun configureStyleSources(loadedStyle: Style) {
        // The bundled raster already covers the globe while ideal HD tiles are loading.
        loadedStyle.getSourceAs<RasterSource>("earth-hd")?.setPrefetchZoomDelta(0)
        if (loadedStyle.getSource("places") == null) {
            val options = GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(32)
                .withClusterMaxZoom(17)
                .withClusterMinPoints(2)
            loadedStyle.addSource(GeoJsonSource("places", FeatureCollection.fromFeatures(emptyArray()), options))
        }
        if (loadedStyle.getSource("selection") == null) {
            loadedStyle.addSource(GeoJsonSource("selection", FeatureCollection.fromFeatures(emptyArray())))
        }
        if (loadedStyle.getLayer("places-index") == null) {
            loadedStyle.addLayer(
                CircleLayer("places-index", "places").withProperties(
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleRadius(1f),
                )
            )
        }
        if (loadedStyle.getLayer("selection-index") == null) {
            loadedStyle.addLayer(
                CircleLayer("selection-index", "selection").withProperties(
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleRadius(1f),
                )
            )
        }
    }

    fun onViewportSized(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val changed = stageWidthPx != widthPx || stageHeightPx != heightPx
        stageWidthPx = widthPx
        stageHeightPx = heightPx
        if (changed) {
            pinsDirty = true
            applyInitialCameraIfNeeded()
            syncHudFromCamera()
            flushPendingFocus()
        }
    }

    private fun applyInitialCameraIfNeeded() {
        val m = map ?: return
        if (initialCameraPlaced || stageWidthPx <= 0 || stageHeightPx <= 0) return
        initialCameraPlaced = true
        val stageWdp = stageWidthPx / density.toDouble()
        val stageHdp = stageHeightPx / density.toDouble()
        val startLat = WorldMapMath.clampLatitude(initialSnapshot?.latitude ?: InitialWorldLatitude)
        val startLon = WorldMapMath.wrapLongitude(initialSnapshot?.longitude ?: InitialWorldLongitude)
        val baseGlobeZ = WorldMapMath.globeZoom(startLat, stageWdp, stageHdp)
        m.setMinZoomPreference(0.0)
        val startZoom = (initialSnapshot?.zoom ?: baseGlobeZ).coerceIn(baseGlobeZ, 18.0)
        val padBottom = currentBottomPaddingPx(startZoom, startLat).toDouble()
        val cam = CameraPosition.Builder()
            .target(LatLng(startLat, startLon))
            .zoom(startZoom)
            .fov(WorldFovDegrees)
            .padding(0.0, 0.0, 0.0, padBottom)
            .build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    private fun currentBottomPaddingPx(
        zoom: Double = map?.cameraPosition?.zoom ?: hudState.zoom,
        lat: Double = map?.cameraPosition?.target?.latitude ?: hudState.latitude,
    ): Int {
        if (summaryOpen && summaryHeightPx > 0) return summaryHeightPx
        return if (WorldMapMath.visualZoom(zoom, lat) < 4.0) {
            WorldMapMath.baseBottomPaddingPx(stageWidthPx, stageHeightPx)
        } else 0
    }

    private fun clampCameraBoundsOnIdle() {
        val m = map ?: return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        val stageWdp = (stageWidthPx / density.toDouble()).coerceAtLeast(360.0)
        val stageHdp = (stageHeightPx / density.toDouble()).coerceAtLeast(560.0)
        val minGlobeEqZ = WorldMapMath.globeEquatorialZoom(stageWdp, stageHdp)
        val curEqZ = max(WorldMapMath.equatorialZoom(pos.zoom, target.latitude), minGlobeEqZ)
        val globeness = WorldMapMath.globenessForZoom(pos.zoom)
        val maxLat = if (globeness >= 0.999f) WorldMapMath.maxConstantSizeGlobeLatitude(curEqZ) else 80.0
        val clampedLat = if (target.latitude.isFinite()) target.latitude.coerceIn(-maxLat, maxLat) else 0.0
        val wrappedLon = WorldMapMath.wrapLongitude(target.longitude)
        val minGlobeZ = WorldMapMath.globeZoom(clampedLat, stageWdp, stageHdp)
        val latChanged = abs(clampedLat - target.latitude) > 0.01
        val zoomBelowMin = pos.zoom < minGlobeZ - 0.02
        if (latChanged || zoomBelowMin) {
            val updatedZoom = if (zoomBelowMin) minGlobeZ else pos.zoom
            val updated = CameraPosition.Builder(pos)
                .target(LatLng(clampedLat, wrappedLon))
                .zoom(updatedZoom)
                .build()
            if (zoomBelowMin && motionEnabled) {
                m.easeCamera(CameraUpdateFactory.newCameraPosition(updated), 180)
            } else {
                m.moveCamera(CameraUpdateFactory.newCameraPosition(updated))
            }
        }
    }

    fun syncData(
        entries: List<WorldPlaceEntry>,
        selection: WorldSelection?,
        focus: WorldFocus?,
        summaryHeightPx: Int,
        visits: Set<String>,
        motion: Boolean,
    ) {
        val entriesChanged = this.entries !== entries
        val visitsChanged = this.visits != visits
        val nextSelectedId = selection?.placeId?.takeIf { id -> entries.any { it.place.id == id } }.orEmpty()
        val selectedChanged = this.selectedId != nextSelectedId
        val nextSummaryOpen = nextSelectedId.isNotEmpty() && selection?.summaryOpen == true
        val summaryChanged = this.summaryOpen != nextSummaryOpen || this.summaryHeightPx != summaryHeightPx

        this.entries = entries
        if (entriesChanged) {
            this.byId = entries.associateBy { it.place.id }
        }
        this.visits = visits
        this.selectedId = nextSelectedId
        val prevMotion = this.motionEnabled
        this.summaryOpen = nextSummaryOpen
        this.summaryHeightPx = summaryHeightPx
        this.motionEnabled = motion
        if (prevMotion && !motion && activeCameraTargetSeq != 0L) {
            val pendingTarget = activeTargetCamera
            activeCameraTargetSeq = 0L
            activeTargetCamera = null
            if (pendingTarget != null) {
                map?.let { m ->
                    m.cancelTransitions()
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(pendingTarget))
                    isMoving = false
                    syncHudFromCamera()
                    persistCameraSnapshot()
                }
            }
        }
        if (focus != null && focus.token > lastAppliedFocusToken) {
            this.pendingFocus = focus
        }

        if (entriesChanged || visitsChanged || selectedChanged) {
            syncGeoJsonSources(force = false)
        }
        if (summaryChanged && ready && pendingFocus == null) {
            val padBottom = currentBottomPaddingPx().toDouble()
            map?.let { m ->
                val cur = m.cameraPosition
                if ((cur.padding?.getOrNull(3) ?: 0.0) != padBottom) {
                    val cam = CameraPosition.Builder(cur).padding(0.0, 0.0, 0.0, padBottom).build()
                    if (motion) m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 220)
                    else m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
                }
            }
        }
        flushPendingFocus()
        persistCameraSnapshot()
    }

    private fun syncGeoJsonSources(force: Boolean) {
        val loadedStyle = style ?: return
        if (!force && lastSyncedEntries === entries && lastSyncedVisits == visits && lastSyncedSelectedId == selectedId) {
            return
        }
        lastSyncedEntries = entries
        lastSyncedVisits = visits.toSet()
        lastSyncedSelectedId = selectedId

        val placeFeatures = ArrayList<Feature>(entries.size)
        var selectedFeature: Feature? = null
        for (entry in entries) {
            val p = entry.place
            val feat = Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
                addStringProperty("id", p.id)
                addStringProperty("name", entry.name)
                addBooleanProperty("visited", p.id in visits)
            }
            if (p.id == selectedId) selectedFeature = feat
            else placeFeatures.add(feat)
        }
        loadedStyle.getSourceAs<GeoJsonSource>("places")?.setGeoJson(FeatureCollection.fromFeatures(placeFeatures))
        loadedStyle.getSourceAs<GeoJsonSource>("selection")?.setGeoJson(
            selectedFeature?.let { FeatureCollection.fromFeature(it) } ?: FeatureCollection.fromFeatures(emptyArray())
        )
        pinsDirty = true
    }

    private fun flushPendingFocus() {
        val focus = pendingFocus ?: return
        if (!ready || !hasVisual || stageWidthPx <= 0 || stageHeightPx <= 0) return
        val entry = byId[focus.placeId] ?: return
        pendingFocus = null
        lastAppliedFocusToken = focus.token
        val m = map ?: return
        val pos = m.cameraPosition
        val currentZoom = pos.zoom
        val currentLat = pos.target?.latitude ?: hudState.latitude
        val stageWdp = (stageWidthPx / density.toDouble()).coerceAtLeast(360.0)
        val stageHdp = (stageHeightPx / density.toDouble()).coerceAtLeast(560.0)
        val targetGlobeMinZ = WorldMapMath.globeZoom(entry.place.latitude, stageWdp, stageHdp)
        val targetZoom = when (focus.kind) {
            WorldFocusKind.Pin -> {
                if (currentZoom < 5.0) {
                    val eqZoom = WorldMapMath.equatorialZoom(currentZoom, currentLat)
                    WorldMapMath.zoomForEquatorialZoom(eqZoom, entry.place.latitude)
                } else {
                    currentZoom
                }
            }
            WorldFocusKind.List -> max(11.0, currentZoom)
            WorldFocusKind.Street -> 15.0
        }.coerceIn(targetGlobeMinZ, 18.0)
        val duration = if (focus.kind == WorldFocusKind.Pin) 360 else 850
        moveCameraTo(
            lat = entry.place.latitude,
            lon = entry.place.longitude,
            zoom = targetZoom,
            paddingBottom = currentBottomPaddingPx(targetZoom, entry.place.latitude).toDouble(),
            durationMs = duration,
            useFly = focus.kind != WorldFocusKind.Pin,
        )
    }

    private fun moveCameraTo(
        lat: Double,
        lon: Double,
        zoom: Double,
        paddingBottom: Double = currentBottomPaddingPx(zoom, lat).toDouble(),
        durationMs: Int = 360,
        useFly: Boolean = false,
    ) {
        val m = map ?: return
        val clampedLat = WorldMapMath.clampLatitude(lat)
        val wrappedLon = WorldMapMath.wrapLongitude(lon)
        val stageWdp = (stageWidthPx / density.toDouble()).coerceAtLeast(360.0)
        val stageHdp = (stageHeightPx / density.toDouble()).coerceAtLeast(560.0)
        val targetMinZoom = WorldMapMath.globeZoom(clampedLat, stageWdp, stageHdp)
        val clampedZoom = zoom.coerceIn(targetMinZoom, 18.0)
        val seq = ++cameraTargetSeq
        activeCameraTargetSeq = seq
        val cam = CameraPosition.Builder()
            .target(LatLng(clampedLat, wrappedLon))
            .zoom(clampedZoom)
            .fov(WorldFovDegrees)
            .padding(0.0, 0.0, 0.0, paddingBottom)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(cam)
        if (!motionEnabled || durationMs <= 0) {
            m.moveCamera(update)
            activeCameraTargetSeq = 0L
            activeTargetCamera = null
            isMoving = false
            syncHudFromCamera()
            persistCameraSnapshot()
            return
        }
        activeTargetCamera = cam
        isMoving = true
        val callback = object : MapLibreMap.CancelableCallback {
            override fun onCancel() {
                if (activeCameraTargetSeq != seq) return
                activeCameraTargetSeq = 0L
                activeTargetCamera = null
                isMoving = false
            }

            override fun onFinish() {
                if (activeCameraTargetSeq != seq) return
                activeCameraTargetSeq = 0L
                activeTargetCamera = null
                isMoving = false
                syncHudFromCamera()
                updateRegionAndCityLabels()
                persistCameraSnapshot()
            }
        }
        if (useFly) m.animateCamera(update, durationMs, callback)
        else m.easeCamera(update, durationMs, true, callback)
    }

    fun zoomBy(delta: Double) {
        val m = map ?: return
        zoomTo(m.cameraPosition.zoom + delta, durationMs = 280)
    }

    fun zoomTo(targetZoom: Double, durationMs: Int = 450) {
        val m = map ?: return
        val cur = m.cameraPosition
        val target = cur.target ?: LatLng(hudState.latitude, hudState.longitude)
        val clamped = targetZoom.coerceIn(hudState.globeZoom, 18.0)
        moveCameraTo(target.latitude, target.longitude, clamped, durationMs = durationMs, useFly = durationMs > 400)
    }

    fun goGlobe() {
        val m = map ?: return
        val cur = m.cameraPosition
        val target = cur.target ?: LatLng(hudState.latitude, hudState.longitude)
        val stageWdp = (stageWidthPx / density.toDouble()).coerceAtLeast(360.0)
        val stageHdp = (stageHeightPx / density.toDouble()).coerceAtLeast(560.0)
        val globeZ = WorldMapMath.globeZoom(target.latitude, stageWdp, stageHdp)
        moveCameraTo(
            lat = target.latitude,
            lon = target.longitude,
            zoom = globeZ,
            paddingBottom = WorldMapMath.baseBottomPaddingPx(stageWidthPx, stageHeightPx).toDouble(),
            durationMs = 800,
            useFly = true,
        )
    }

    fun focusCity(lat: Double, lon: Double, zoom: Double = 11.0) {
        moveCameraTo(lat, lon, zoom, durationMs = 650, useFly = true)
    }

    fun handleBack(): Boolean {
        val m = map ?: return false
        if (m.cameraPosition.zoom > hudState.globeZoom + 0.15) {
            goGlobe()
            return true
        }
        return false
    }

    fun retryTiles() {
        lastRetryAtMs = System.currentTimeMillis()
        tileLoadingSinceMs = lastRetryAtMs
        WorldTilePipeline.resetErrorFlags()
        val m = map ?: return
        val cur = m.cameraPosition
        val styleJson = WorldTilePipeline.buildWorldStyleJson()
        m.setStyle(Style.Builder().fromJson(styleJson)) { loaded ->
            if (destroyed) return@setStyle
            style = loaded
            configureStyleSources(loaded)
            syncGeoJsonSources(force = true)
            m.moveCamera(CameraUpdateFactory.newCameraPosition(cur))
            updateLoadingState()
        }
    }

    fun handlePinAccessibilityClick(pin: ProjectedPin) {
        if (pin.clusterId != null) {
            expandClusterById(pin)
        } else if (!pin.placeId.isNullOrEmpty()) {
            onTapEvent?.invoke(WorldTapEvent.SelectPlace(pin.placeId))
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val m = map ?: return false
        if (destroyed || !ready) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mapView.parent?.requestDisallowInterceptTouchEvent(true)
                m.cancelTransitions()
                activeCameraTargetSeq = 0L
                activeTargetCamera = null
                val pos = m.cameraPosition
                touchMode = TouchMode.DRAG
                gestureDownX = event.x
                gestureDownY = event.y
                gestureDownTimeMs = event.eventTime
                gestureMoved = false
                gestureStartLat = WorldMapMath.clampLatitude(pos.target?.latitude ?: hudState.latitude)
                gestureStartZoom = pos.zoom
                gestureStartEqZoom = WorldMapMath.equatorialZoom(pos.zoom, gestureStartLat)
                gestureAnchor = sampleAnchor(m, event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    mapView.parent?.requestDisallowInterceptTouchEvent(true)
                    m.cancelTransitions()
                    activeCameraTargetSeq = 0L
                    activeTargetCamera = null
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val span = hypot(
                        event.getX(1) - event.getX(0),
                        event.getY(1) - event.getY(0),
                    ).coerceAtLeast(16f)
                    val pos = m.cameraPosition
                    touchMode = TouchMode.PINCH
                    gestureDownX = midX
                    gestureDownY = midY
                    pinchStartSpan = span
                    pinchStartZoom = pos.zoom
                    pinchMaxMove = 0f
                    gestureStartLat = WorldMapMath.clampLatitude(pos.target?.latitude ?: hudState.latitude)
                    gestureStartZoom = pos.zoom
                    gestureStartEqZoom = WorldMapMath.equatorialZoom(pos.zoom, gestureStartLat)
                    gestureAnchor = sampleAnchor(m, midX, midY)
                    isMoving = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.PINCH -> if (event.pointerCount >= 2) {
                        val midX = (event.getX(0) + event.getX(1)) * 0.5f
                        val midY = (event.getY(0) + event.getY(1)) * 0.5f
                        val span = hypot(
                            event.getX(1) - event.getX(0),
                            event.getY(1) - event.getY(0),
                        ).coerceAtLeast(16f)
                        pinchMaxMove = max(pinchMaxMove, hypot(midX - gestureDownX, midY - gestureDownY))
                        val nextZoom = pinchStartZoom + log2((span / pinchStartSpan).toDouble())
                        if (abs(nextZoom - pinchStartZoom) > 0.01 || pinchMaxMove > 2f * density) {
                            gestureMoved = true
                        }
                        val anchor = gestureAnchor ?: sampleAnchor(m, midX, midY).also { gestureAnchor = it }
                        if (anchor != null) {
                            isMoving = true
                            val targetEqZoom = WorldMapMath.equatorialZoom(nextZoom, gestureStartLat)
                            solveAnchorToPoint(m, anchor, PointF(midX, midY), nextZoom, targetEqZoom)
                        }
                    }
                    TouchMode.DRAG -> if (event.pointerCount == 1) {
                        val dist = hypot(event.x - gestureDownX, event.y - gestureDownY)
                        if (!gestureMoved && dist >= 2.5f * density) {
                            gestureMoved = true
                            isMoving = true
                        }
                        if (gestureMoved) {
                            val anchor = gestureAnchor ?: sampleAnchor(m, event.x, event.y).also { gestureAnchor = it }
                            if (anchor != null) {
                                solveAnchorToPoint(m, anchor, PointF(event.x, event.y), gestureStartZoom, gestureStartEqZoom)
                            }
                        }
                    }
                    TouchMode.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchMode == TouchMode.PINCH && event.pointerCount == 2) {
                    val elapsed = event.eventTime - gestureDownTimeMs
                    val isQuickTwoFingerTap = !gestureMoved &&
                        pinchMaxMove < 10f * density &&
                        abs(m.cameraPosition.zoom - pinchStartZoom) < 0.12 &&
                        elapsed < 350L
                    if (!isQuickTwoFingerTap) {
                        val remainIdx = if (event.actionIndex == 0) 1 else 0
                        val rx = event.getX(remainIdx)
                        val ry = event.getY(remainIdx)
                        val pos = m.cameraPosition
                        touchMode = TouchMode.DRAG
                        gestureDownX = rx
                        gestureDownY = ry
                        gestureMoved = true
                        gestureStartLat = WorldMapMath.clampLatitude(pos.target?.latitude ?: hudState.latitude)
                        gestureStartZoom = pos.zoom
                        gestureStartEqZoom = WorldMapMath.equatorialZoom(pos.zoom, gestureStartLat)
                        gestureAnchor = sampleAnchor(m, rx, ry)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mapView.parent?.requestDisallowInterceptTouchEvent(false)
                val prevMode = touchMode
                val wasMoved = gestureMoved
                val elapsed = event.eventTime - gestureDownTimeMs
                val upX = event.x
                val upY = event.y
                val isUp = event.actionMasked == MotionEvent.ACTION_UP

                touchMode = TouchMode.NONE
                gestureAnchor = null
                gestureMoved = false
                isMoving = false

                if (isUp) {
                    when {
                        prevMode == TouchMode.PINCH && !wasMoved && elapsed < 350L -> {
                            zoomBy(-1.0)
                        }
                        prevMode == TouchMode.DRAG && !wasMoved && elapsed < 400L -> {
                            val handled = handleMapTap(upX, upY)
                            if (!handled) {
                                val isDoubleTap = (event.eventTime - lastTapTimeMs) < 280L &&
                                    hypot(upX - lastTapX, upY - lastTapY) < 28f * density
                                if (isDoubleTap) {
                                    lastTapTimeMs = 0L
                                    zoomBy(1.0)
                                } else {
                                    lastTapTimeMs = event.eventTime
                                    lastTapX = upX
                                    lastTapY = upY
                                }
                            } else {
                                lastTapTimeMs = 0L
                            }
                        }
                        wasMoved -> {
                            pinsDirty = true
                            clampCameraBoundsOnIdle()
                            syncHudFromCamera()
                            updateRegionAndCityLabels()
                            persistCameraSnapshot()
                            updateLoadingState()
                        }
                    }
                } else if (wasMoved) {
                    pinsDirty = true
                    syncHudFromCamera()
                }
                return true
            }
        }
        return true
    }

    private fun sampleAnchor(m: MapLibreMap, xPx: Float, yPx: Float): LatLng? {
        val pos = m.cameraPosition
        val target = pos.target ?: return null
        val padBottom = currentBottomPaddingPx(pos.zoom, target.latitude).toDouble()
        val pt = clampPointToGlobe(PointF(xPx, yPx), pos.zoom, target.latitude, padBottom)
        val geo = runCatching { m.projection.fromScreenLocation(pt) }.getOrNull() ?: return null
        if (!geo.latitude.isFinite() || !geo.longitude.isFinite()) return null
        if (runCatching { m.projection.isLocationOccluded(geo) }.getOrDefault(false)) return null
        return LatLng(WorldMapMath.clampLatitude(geo.latitude), WorldMapMath.wrapLongitude(geo.longitude))
    }

    private fun clampPointToGlobe(rawPt: PointF, zoom: Double, centerLat: Double, padBottom: Double): PointF {
        if (zoom >= 6.0 || stageWidthPx <= 0 || stageHeightPx <= 0) return rawPt
        val cx = stageWidthPx * 0.5f
        val cy = ((stageHeightPx - padBottom.toFloat()) * 0.5f).coerceAtLeast(1f)
        val bounds = WorldMapMath.globeBounds(
            zoom = zoom,
            latitude = centerLat,
            centerX = cx,
            centerY = cy,
            stageHeightPx = stageHeightPx.toFloat(),
            density = density,
        )
        val maxR = (bounds.radius * 0.92f).coerceAtLeast(24f)
        val dx = rawPt.x - cx
        val dy = rawPt.y - cy
        val d = hypot(dx, dy)
        return if (d > maxR && d > 1e-3f) {
            PointF(cx + dx * maxR / d, cy + dy * maxR / d)
        } else {
            rawPt
        }
    }

    private fun solveAnchorToPoint(
        m: MapLibreMap,
        anchor: LatLng,
        rawTargetPx: PointF,
        rawZoom: Double,
        targetEqZoom: Double,
    ) {
        val cur = m.cameraPosition
        val curTarget = cur.target ?: return
        val stageWdp = (stageWidthPx / density.toDouble()).coerceAtLeast(360.0)
        val stageHdp = (stageHeightPx / density.toDouble()).coerceAtLeast(560.0)
        val minGlobeEqZoom = WorldMapMath.globeEquatorialZoom(stageWdp, stageHdp)
        val clampedEqZoom = max(targetEqZoom, minGlobeEqZoom)
        val globeness = WorldMapMath.globenessForZoom(rawZoom)
        val maxLat = if (globeness >= 0.999f) WorldMapMath.maxConstantSizeGlobeLatitude(clampedEqZoom) else 80.0
        fun clampLat(lat: Double): Double = if (!lat.isFinite()) 0.0 else lat.coerceIn(-maxLat, maxLat)
        fun zoomForLat(lat: Double): Double {
            val minZ = WorldMapMath.globeZoom(lat, stageWdp, stageHdp)
            if (globeness <= 0.001f) return rawZoom.coerceIn(minZ, 18.0)
            val eqZ = WorldMapMath.zoomForEquatorialZoom(clampedEqZoom, lat)
            val blended = if (globeness >= 0.999f) eqZ else rawZoom + (eqZ - rawZoom) * globeness.toDouble()
            return blended.coerceIn(minZ, 18.0)
        }

        val centerLat = clampLat(curTarget.latitude)
        val centerLon = WorldMapMath.wrapLongitude(curTarget.longitude)
        val startZoom = zoomForLat(centerLat)
        val padBottom = currentBottomPaddingPx(startZoom, centerLat).toDouble()
        val targetPx = clampPointToGlobe(rawTargetPx, startZoom, centerLat, padBottom)

        solvingCamera = true
        try {
            if (centerLat != curTarget.latitude || centerLon != curTarget.longitude ||
                abs(startZoom - cur.zoom) > 1e-9 || padBottom != (cur.padding?.getOrNull(3) ?: 0.0)
            ) {
                applyImmediateCamera(m, centerLat, centerLon, startZoom, padBottom)
            }
            var bestLat = centerLat
            var bestLon = centerLon
            var bestZoom = startZoom
            var bestErr = screenError(m, anchor, targetPx)

            for (iter in 0 until 6) {
                if (bestErr < 0.35f) break
                val atTarget = runCatching { m.projection.fromScreenLocation(targetPx) }.getOrNull() ?: break
                if (!atTarget.latitude.isFinite() || !atTarget.longitude.isFinite()) break
                val dLat = anchor.latitude - atTarget.latitude
                val dLon = wrapDeltaLongitude(anchor.longitude - atTarget.longitude)
                val nextLat = clampLat(bestLat + dLat)
                val nextLon = WorldMapMath.wrapLongitude(bestLon + dLon)
                val nextZoom = zoomForLat(nextLat)
                applyImmediateCamera(m, nextLat, nextLon, nextZoom, padBottom)
                val err = screenError(m, anchor, targetPx)
                if (err < bestErr) {
                    bestLat = nextLat
                    bestLon = nextLon
                    bestZoom = nextZoom
                    bestErr = err
                } else {
                    val halfLat = clampLat(bestLat + dLat * 0.5)
                    val halfLon = WorldMapMath.wrapLongitude(bestLon + dLon * 0.5)
                    val halfZoom = zoomForLat(halfLat)
                    applyImmediateCamera(m, halfLat, halfLon, halfZoom, padBottom)
                    val halfErr = screenError(m, anchor, targetPx)
                    if (halfErr < bestErr) {
                        bestLat = halfLat
                        bestLon = halfLon
                        bestZoom = halfZoom
                        bestErr = halfErr
                    } else {
                        applyImmediateCamera(m, bestLat, bestLon, bestZoom, padBottom)
                        break
                    }
                }
            }
        } finally {
            solvingCamera = false
        }
    }

    private fun applyImmediateCamera(
        m: MapLibreMap,
        lat: Double,
        lon: Double,
        zoom: Double,
        padBottom: Double,
    ) {
        val cam = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .fov(WorldFovDegrees)
            .padding(0.0, 0.0, 0.0, padBottom)
            .build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    private fun screenError(m: MapLibreMap, anchor: LatLng, targetPx: PointF): Float {
        val pt = runCatching { m.projection.toScreenLocation(anchor) }.getOrNull() ?: return Float.MAX_VALUE
        if (!pt.x.isFinite() || !pt.y.isFinite()) return Float.MAX_VALUE
        return hypot(pt.x - targetPx.x, pt.y - targetPx.y)
    }

    private fun wrapDeltaLongitude(delta: Double): Double =
        ((delta + 540.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun handleMapTap(xPx: Float, yPx: Float): Boolean {
        val reachPx = 24f * density
        val hit = visiblePins
            .filter { pin ->
                val pad = reachPx + (pin.radius + hypot(pin.hx - pin.x, pin.hy - pin.y)) * density
                kotlin.math.abs(pin.x - xPx) <= pad && kotlin.math.abs(pin.y - yPx) <= pad
            }
            .minByOrNull { pin -> hitScore(pin, xPx, yPx) }
        if (hit == null) {
            if (summaryOpen && selectedId.isNotEmpty()) {
                onTapEvent?.invoke(WorldTapEvent.DismissSummary(selectedId))
                return true
            }
            return false
        }
        val zoom = map?.cameraPosition?.zoom ?: hudState.zoom
        if (zoom >= 17.8 && hit.placeId != null) {
            val coincident = visiblePins
                .filter { pin ->
                    pin.placeId != null &&
                        kotlin.math.abs(pin.x - xPx) <= reachPx + pin.radius * density &&
                        kotlin.math.abs(pin.y - yPx) <= reachPx + pin.radius * density
                }
                .mapNotNull { it.placeId }
                .distinct()
            if (coincident.size > 1) {
                onTapEvent?.invoke(WorldTapEvent.ChoosePlaces(coincident))
                return true
            }
        }
        if (hit.clusterId != null) {
            expandClusterById(hit)
            return true
        }
        hit.placeId?.let { id ->
            onTapEvent?.invoke(WorldTapEvent.SelectPlace(id))
            return true
        }
        return false
    }

    private fun hitScore(pin: ProjectedPin, x: Float, y: Float): Float {
        val rPx = pin.radius * density
        val dHead = hypot(x - pin.hx, y - pin.hy)
        val dBase = hypot(x - pin.x, y - pin.y)
        if (dHead <= rPx + 1.5f * density) return dHead - rPx - 20f * density
        if (dBase <= rPx * 0.85f) return dBase - rPx - 12f * density
        return min(dHead, dBase + 2.5f * density)
    }

    private fun expandClusterById(pin: ProjectedPin) {
        val loadedStyle = style ?: return
        val source = loadedStyle.getSourceAs<GeoJsonSource>("places") ?: return
        val clusterId = pin.clusterId ?: return
        val clusterFeatures = source.querySourceFeatures(Expression.eq(Expression.get("cluster_id"), Expression.literal(clusterId)))
        val clusterFeature = clusterFeatures.firstOrNull()
        if (clusterFeature == null) {
            moveCameraTo(pin.lat, pin.lon, ((map?.cameraPosition?.zoom ?: 2.0) + 2.0).coerceAtMost(18.0), durationMs = 600, useFly = true)
            return
        }
        val expansionZoom = runCatching { source.getClusterExpansionZoom(clusterFeature) }.getOrDefault(18)
        val currentZoom = map?.cameraPosition?.zoom ?: hudState.zoom
        if (currentZoom >= 17.8 || expansionZoom >= 18) {
            val leaves = runCatching { source.getClusterLeaves(clusterFeature, entries.size.toLong().coerceAtLeast(20L), 0L).features() }
                .getOrNull().orEmpty()
            val ids = leaves.mapNotNull { leaf ->
                runCatching { leaf.getStringProperty("id") }.getOrNull()
            }.distinct()
            if (ids.size > 1) {
                onTapEvent?.invoke(WorldTapEvent.ChoosePlaces(ids))
            } else if (ids.size == 1) {
                onTapEvent?.invoke(WorldTapEvent.SelectPlace(ids.first()))
            }
        } else {
            val targetZoom = min(18.0, max(currentZoom + 1.0, expansionZoom + 0.3))
            moveCameraTo(pin.lat, pin.lon, targetZoom, durationMs = 650, useFly = true)
        }
    }

    private fun syncHudFromCamera() {
        val m = map ?: return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        val lat = WorldMapMath.clampLatitude(target.latitude)
        val lon = WorldMapMath.wrapLongitude(target.longitude)
        val zoom = pos.zoom
        val stageWdp = (stageWidthPx / density.toDouble()).coerceAtLeast(360.0)
        val stageHdp = (stageHeightPx / density.toDouble()).coerceAtLeast(560.0)
        val minGlobeZ = WorldMapMath.globeZoom(lat, stageWdp, stageHdp)
        val visualZ = WorldMapMath.visualZoom(zoom, lat)
        val (scaleLabel, scaleWidthPx) = WorldMapMath.scaleBarMetric(zoom, lat, density)
        hudState = hudState.copy(
            longitude = lon,
            latitude = lat,
            zoom = zoom,
            visualZoom = visualZ,
            globeZoom = minGlobeZ,
            compactHeader = visualZ >= 3.0,
            enlarged = zoom > minGlobeZ + 0.05,
            beyondGlobe = visualZ >= 4.0,
            satelliteScale = zoom < 8.0,
            levelLabel = WorldMapMath.levelLabel(visualZ),
            scaleLabel = scaleLabel,
            scaleWidthPx = scaleWidthPx,
        )
    }

    private fun updateRegionAndCityLabels() {
        val m = map ?: return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        val zoom = pos.zoom
        val lon = WorldMapMath.wrapLongitude(target.longitude)
        val lat = WorldMapMath.clampLatitude(target.latitude)
        val cosLat = max(0.087, cos(Math.toRadians(lat)))

        var nearestEntry: WorldPlaceEntry? = null
        var minDistSq = Double.POSITIVE_INFINITY
        for (entry in entries) {
            val dLat = entry.place.latitude - lat
            val dLon = WorldMapMath.wrapLongitude(entry.place.longitude - lon) * cosLat
            val d2 = dLat * dLat + dLon * dLon
            if (d2 < minDistSq) {
                minDistSq = d2
                nearestEntry = entry
            }
        }
        val degrees = if (nearestEntry != null) sqrt(minDistSq) else Double.POSITIVE_INFINITY
        val centerRegion = if (countries.isNotEmpty()) WorldCountryLookup.countryAt(countries, lon, lat) else ""
        val visibleBounds = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()
        val visibleCity = nearestEntry != null && zoom >= 7.0 &&
            (visibleBounds?.contains(LatLng(nearestEntry.place.latitude, nearestEntry.place.longitude)) == true)
        val nearbyRegion = if (visibleCity && degrees < 0.2) nearestEntry else null
        val country = nearbyRegion?.place?.country?.let(::canonicalRegionName) ?: centerRegion
        val regionName = if (WorldMapMath.visualZoom(zoom, lat) >= 3.0 && country.isNotBlank()) country else ""
        val rawCity = nearestEntry?.let { unifiedDestinationRegion(canonicalRegionName(it.place.country)) ?: it.place.city }
        val cityName = if (visibleCity && !rawCity.isNullOrBlank() && rawCity != country) rawCity else ""
        if (hudState.regionName != regionName || hudState.cityName != cityName) {
            hudState = hudState.copy(regionName = regionName, cityName = cityName)
        }
    }

    private fun updateLoadingState() {
        val zoom = hudState.zoom
        if (zoom < 1.25 || !WorldTilePipeline.hasKey()) {
            hudState = hudState.copy(loadingVisible = false, progressVisible = false, loadingError = false)
            return
        }
        val imageStage = zoom < 6.0
        val error = if (imageStage) {
            WorldTilePipeline.recentHdErrorSince(lastRetryAtMs)
        } else {
            WorldTilePipeline.recentStreetErrorSince(lastRetryAtMs)
        }
        val active = activeTileRequests.isNotEmpty()
        if (!active && !error) {
            hudState = hudState.copy(loadingVisible = false, progressVisible = false, loadingError = false)
            return
        }
        val elapsed = if (tileLoadingSinceMs > 0L) System.currentTimeMillis() - tileLoadingSinceMs else 0L
        val showProgress = active && elapsed >= 300L
        val showLoading = error || (active && elapsed >= 800L)
        val text = when {
            imageStage && error -> "高清地球暂不可用 · 重试"
            imageStage -> "正在加载高清地球"
            error -> "街道暂不可用 · 重试"
            else -> "正在加载街道"
        }
        hudState = hudState.copy(
            loadingVisible = showLoading,
            progressVisible = showProgress,
            loadingError = error,
            loadingText = text,
        )
    }

    private fun recomputePins(pos: CameraPosition) {
        val m = map ?: return
        val loadedStyle = style ?: return
        if (stageWidthPx <= 0 || stageHeightPx <= 0) return

        val projection = m.projection
        val zoom = pos.zoom
        pinZoom = zoom
        val globeness = WorldMapMath.globenessForZoom(zoom)
        currentGlobeness = globeness

        val centerLatLng = pos.target ?: LatLng(InitialWorldLatitude, InitialWorldLongitude)
        val centerScreen = projection.toScreenLocation(centerLatLng)
        val bounds = if (globeness > 0.01f) {
            val fallback = WorldMapMath.globeBounds(
                zoom = zoom,
                latitude = centerLatLng.latitude,
                centerX = centerScreen.x,
                centerY = centerScreen.y,
                stageHeightPx = stageHeightPx.toFloat(),
                density = density,
            )
            val measuredRadius = if (globeness >= 0.99f && centerScreen.x.isFinite() && centerScreen.y.isFinite()) {
                measureRenderedGlobeRadius(projection, centerLatLng, centerScreen, fallback.radius)
            } else null
            if (measuredRadius != null) {
                GlobeBounds(centerScreen.x, centerScreen.y, measuredRadius)
            } else {
                fallback
            }
        } else null
        activeGlobeBounds = bounds
        updateGlobeClipOutline(bounds, globeness)

        val lighting = ((4.2 - zoom) / 3.0).toFloat().coerceIn(0f, 1f) * globeness
        val stand = max(WorldMapMath.smoothstep(3.2f, 7.2f, zoom.toFloat()), 1f - globeness)
        val dotDp = WorldMapMath.placeRadiusDp(zoom)

        val placesSource = loadedStyle.getSourceAs<GeoJsonSource>("places")
        val selectionSource = loadedStyle.getSourceAs<GeoJsonSource>("selection")
        val selectionFeatures = selectionSource?.querySourceFeatures(null).orEmpty()
        val placeFeatures = placesSource?.querySourceFeatures(null).orEmpty()

        val seen = HashSet<String>(selectionFeatures.size + placeFeatures.size)
        val candidates = ArrayList<ProjectedPin>(selectionFeatures.size + placeFeatures.size)
        val total = selectionFeatures.size + placeFeatures.size
        val marginX = 36f * density
        val marginY = 48f * density

        for (i in 0 until total) {
            val isSelected = i < selectionFeatures.size
            val feat = if (isSelected) selectionFeatures[i] else placeFeatures[i - selectionFeatures.size]
            val point = feat.geometry() as? Point ?: continue
            val count = if (feat.hasProperty("point_count")) feat.getNumberProperty("point_count").toInt() else 0
            val clusterId = if (count > 0 && feat.hasProperty("cluster_id")) feat.getNumberProperty("cluster_id").toLong() else null
            val placeId = if (count == 0 && feat.hasProperty("id")) feat.getStringProperty("id") else null
            val key = if (clusterId != null) "cluster-$clusterId" else (placeId ?: continue)
            if (!seen.add(key)) continue

            val latLng = LatLng(point.latitude(), point.longitude())
            val screen = projection.toScreenLocation(latLng)
            if (!screen.x.isFinite() || !screen.y.isFinite()) continue
            if (screen.x < -marginX || screen.x > stageWidthPx + marginX || screen.y < -marginY || screen.y > stageHeightPx + marginY) {
                continue
            }
            if (globeness > 0f && projection.isLocationOccluded(latLng)) continue

            var nx = 0f
            var ny = 0f
            var face = 1f
            if (bounds != null && bounds.radius > 1f) {
                nx = (screen.x - bounds.x) / bounds.radius
                ny = (screen.y - bounds.y) / bounds.radius
                val d = hypot(nx, ny)
                if (d > 1f) {
                    nx /= d
                    ny /= d
                }
                face = sqrt(max(0f, 1f - nx * nx - ny * ny))
            }

            val opacity = 1f - globeness * (1f - WorldMapMath.smoothstep(0.08f, 0.28f, face))
            if (opacity <= 0.01f) continue

            var radiusDp = when {
                count > 0 -> {
                    val grow = 1f + 0.16f * WorldMapMath.smoothstep(1.5f, 10f, zoom.toFloat())
                    (when {
                        count >= 100 -> 11.2f
                        count >= 20 -> 9.8f
                        count >= 5 -> 8.6f
                        else -> 7.6f
                    }) * grow
                }
                isSelected -> dotDp + 2.4f
                else -> dotDp
            }
            radiusDp *= WorldMapMath.mix(1f, 0.84f, globeness * (1f - WorldMapMath.smoothstep(0.18f, 0.65f, face)))

            val globeStemDp = (if (count > 0) 4.2f else if (isSelected) 9.5f else 5.8f) * (0.35f + 0.65f * hypot(nx, ny))
            val streetStemDp = if (count > 0) 5.5f else if (isSelected) 16.5f else 11.5f
            val hx = screen.x + WorldMapMath.mix(nx * globeStemDp, 0f, stand) * density
            val hy = screen.y + WorldMapMath.mix(ny * globeStemDp, -streetStemDp, stand) * density

            val ratio = WorldMapMath.mix(max(0.22f, face), 1f, stand)
            val angle = if (bounds != null) atan2(ny, nx) else 0f

            val facing = -0.49f * nx + -0.55f * ny + 0.68f * face
            val shadow = 0.26f * (1f - WorldMapMath.smoothstep(-0.26f, 0.72f, facing)) * lighting
            val spec = max(0f, (facing - 0.42f) / 0.58f)
            val highlight = 0.14f * spec * spec * lighting
            val visited = !isSelected && count == 0 && feat.hasProperty("visited") && feat.getBooleanProperty("visited")
            val labelName = if (count > 0) "$count 个地点" else (feat.getStringProperty("name") ?: "")

            candidates.add(
                ProjectedPin(
                    key = key,
                    placeId = placeId,
                    clusterId = clusterId,
                    name = labelName,
                    pointCount = count,
                    visited = visited,
                    isSelected = isSelected,
                    x = screen.x,
                    y = screen.y,
                    hx = hx,
                    hy = hy,
                    radius = radiusDp,
                    ratio = ratio,
                    angle = angle,
                    face = face,
                    stand = stand,
                    opacity = opacity,
                    light = (1f - shadow) * (1f - highlight * 0.5f),
                    highlight = highlight,
                    lat = point.latitude(),
                    lon = point.longitude(),
                )
            )
        }

        val selectionPin = candidates.firstOrNull { it.isSelected }
        val currentSelectedKey = selectionPin?.key
        if (currentSelectedKey != lastSelectedKey) {
            lastSelectedKey = currentSelectedKey
            if (currentSelectedKey != null) {
                selectionPulseTick++
            }
        }

        visiblePins = candidates
            .filter { pin -> selectionPin == null || pin.clusterId == null || !clusterOverlapsSelection(pin, selectionPin, density) }
            .sortedWith { a, b ->
                when {
                    a.isSelected != b.isSelected -> if (b.isSelected) 1 else -1
                    stand < 0.5f -> b.face.compareTo(a.face)
                    else -> b.y.compareTo(a.y)
                }
            }
    }

    private fun measureRenderedGlobeRadius(
        projection: Projection,
        center: LatLng,
        centerScreen: PointF,
        fallbackRadius: Float,
    ): Float? {
        val dir = if (center.latitude >= 0.0) -1.0 else 1.0
        val lat1 = center.latitude + dir * 22.0
        val lat2 = center.latitude + dir * 44.0
        val p1Geo = LatLng(lat1, center.longitude)
        val p2Geo = LatLng(lat2, center.longitude)
        if (runCatching { projection.isLocationOccluded(p1Geo) || projection.isLocationOccluded(p2Geo) }.getOrDefault(true)) {
            return null
        }
        val p1 = runCatching { projection.toScreenLocation(p1Geo) }.getOrNull() ?: return null
        val p2 = runCatching { projection.toScreenLocation(p2Geo) }.getOrNull() ?: return null
        if (!p1.x.isFinite() || !p1.y.isFinite() || !p2.x.isFinite() || !p2.y.isFinite()) return null
        val s1 = hypot((p1.x - centerScreen.x).toDouble(), (p1.y - centerScreen.y).toDouble())
        val s2 = hypot((p2.x - centerScreen.x).toDouble(), (p2.y - centerScreen.y).toDouble())
        val measured = WorldMapMath.measureGlobeRadiusFromProjection(
            s1 = s1,
            theta1Rad = Math.toRadians(22.0),
            s2 = s2,
            theta2Rad = Math.toRadians(44.0),
        )?.toFloat() ?: return null
        return measured.takeIf { it in (fallbackRadius * 0.75f)..(fallbackRadius * 1.25f) }
    }

    private fun updateGlobeClipOutline(bounds: GlobeBounds?, globeness: Float) {
        val w = stageWidthPx.toFloat()
        val h = stageHeightPx.toFloat()
        val shouldClip = if (globeness >= 0.99f && bounds != null && w > 0f && h > 0f) {
            val maxCornerDist = max(
                max(hypot(bounds.x, bounds.y), hypot(w - bounds.x, bounds.y)),
                max(hypot(bounds.x, h - bounds.y), hypot(w - bounds.x, h - bounds.y)),
            )
            bounds.radius < maxCornerDist + 4f
        } else {
            false
        }
        if (shouldClip && bounds != null) {
            val nextR = (bounds.radius - WorldMapMath.globeClipInsetPx(density)).coerceAtLeast(1f)
            val changed = !clipGlobeActive ||
                abs(clipGlobeX - bounds.x) > 0.25f ||
                abs(clipGlobeY - bounds.y) > 0.25f ||
                abs(clipGlobeR - nextR) > 0.25f
            clipGlobeActive = true
            clipGlobeX = bounds.x
            clipGlobeY = bounds.y
            clipGlobeR = nextR
            if (!mapView.clipToOutline) {
                mapView.clipToOutline = true
            }
            if (changed) {
                mapView.invalidateOutline()
            }
        } else if (clipGlobeActive || mapView.clipToOutline) {
            clipGlobeActive = false
            mapView.clipToOutline = false
            mapView.invalidateOutline()
        }
    }

    private fun clusterOverlapsSelection(cluster: ProjectedPin, selection: ProjectedPin, density: Float): Boolean {
        val cR = (cluster.radius + 1.5f) * density
        val sR = (selection.radius + 1.5f) * density
        if (hypot(cluster.hx - selection.hx, cluster.hy - selection.hy) <= cR + sR) return true

        val dx = cluster.hx - selection.x
        val dy = cluster.hy - selection.y
        val stemX = selection.hx - selection.x
        val stemY = selection.hy - selection.y
        val stemLenSq = stemX * stemX + stemY * stemY
        if (stemLenSq > (1.2f * density).let { it * it }) {
            val t = ((dx * stemX + dy * stemY) / stemLenSq).coerceIn(0f, 1f)
            if (hypot(dx - t * stemX, dy - t * stemY) <= cR + density) return true
        }
        val ringPx = (8.2f + 2.2f * selection.stand) * density
        val cosA = cos(selection.angle)
        val sinA = sin(selection.angle)
        val radial = (dx * cosA + dy * sinA) / (ringPx * selection.ratio + cR + 1.1f * density)
        val tangent = (dy * cosA - dx * sinA) / (ringPx + cR + 1.1f * density)
        return radial * radial + tangent * tangent <= 1f
    }

    private fun persistCameraSnapshot() {
        val m = map ?: return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        browse.world(
            WorldCameraSnapshot(
                longitude = WorldMapMath.wrapLongitude(target.longitude),
                latitude = WorldMapMath.clampLatitude(target.latitude),
                zoom = pos.zoom.coerceIn(0.0, 18.0),
                selected = selectedId,
                summaryOpen = summaryOpen && selectedId.isNotEmpty(),
            )
        )
    }

    fun screenToLatLng(xPx: Float, yPx: Float): Pair<Double, Double>? {
        val m = map ?: return null
        val ll = runCatching { m.projection.fromScreenLocation(PointF(xPx, yPx)) }.getOrNull() ?: return null
        return ll.latitude to WorldMapMath.wrapLongitude(ll.longitude)
    }

    fun latLngToScreen(lat: Double, lon: Double): Pair<Float, Float>? {
        val m = map ?: return null
        val pt = runCatching { m.projection.toScreenLocation(LatLng(lat, lon)) }.getOrNull() ?: return null
        return pt.x to pt.y
    }

    fun jumpTo(lat: Double, lon: Double, zoom: Double) {
        val m = map ?: return
        activeCameraTargetSeq = 0L
        activeTargetCamera = null
        val clampedLat = WorldMapMath.clampLatitude(lat)
        val cam = CameraPosition.Builder()
            .target(LatLng(clampedLat, WorldMapMath.wrapLongitude(lon)))
            .zoom(zoom.coerceIn(hudState.globeZoom, 18.0))
            .fov(WorldFovDegrees)
            .padding(0.0, 0.0, 0.0, currentBottomPaddingPx(zoom, clampedLat).toDouble())
            .build()
        m.cancelTransitions()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
        isMoving = false
        syncHudFromCamera()
        persistCameraSnapshot()
    }

    fun hasPendingCameraTarget(): Boolean = activeCameraTargetSeq != 0L

    fun diagnostics(): WorldDiagnosticsSnapshot {
        val pos = map?.cameraPosition
        val target = pos?.target
        return WorldDiagnosticsSnapshot(
            ready = ready,
            hasVisual = hasVisual,
            moving = isMoving,
            longitude = WorldMapMath.wrapLongitude(target?.longitude ?: hudState.longitude),
            latitude = WorldMapMath.clampLatitude(target?.latitude ?: hudState.latitude),
            zoom = pos?.zoom ?: hudState.zoom,
            selected = selectedId,
            summaryOpen = summaryOpen,
            placesCount = entries.size,
            visiblePinsCount = visiblePins.size,
            globeness = currentGlobeness,
        )
    }

    fun onStart() {
        if (!destroyed) mapView.onStart()
    }

    fun onResume() {
        if (!destroyed) {
            mapView.onResume()
            mainHandler.removeCallbacks(loadingTicker)
            mainHandler.post(loadingTicker)
        }
    }

    fun onPause() {
        if (!destroyed) {
            mainHandler.removeCallbacks(loadingTicker)
            persistCameraSnapshot()
            mapView.onPause()
        }
    }

    fun onStop() {
        if (!destroyed) {
            persistCameraSnapshot()
            mapView.onStop()
        }
    }

    fun onLowMemory() {
        if (!destroyed) mapView.onLowMemory()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        onTapEvent = null
        mainHandler.removeCallbacks(loadingTicker)
        scope.cancel()
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
    }
}
