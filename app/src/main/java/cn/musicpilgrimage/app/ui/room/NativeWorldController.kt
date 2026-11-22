package cn.musicpilgrimage.app.ui.room

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.data.canonicalRegionName
import cn.musicpilgrimage.app.data.unifiedDestinationRegion
import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.maps.AnnotatedFeature
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CameraState
import com.mapbox.maps.ConstrainMode
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapLoadingErrorType
import com.mapbox.maps.MapOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.SourceDataLoadedType
import com.mapbox.maps.Style
import com.mapbox.maps.ViewAnnotationOptions
import com.mapbox.maps.applyDefaultParams
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.animation.flyTo
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
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
internal const val WorldFovDegrees = 36.86989764584402
internal const val MapboxGlobeZoomOffset = 0.48

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
    val zoom: Double = 0.9,
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
    val streetOffline: Boolean = false,
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
@OptIn(MapboxExperimental::class)
internal class NativeWorldController(
    context: Context,
    private val browse: RoomBrowseState,
    private val initialSnapshot: WorldCameraSnapshot? = browse.worldState,
) {
    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val accessibility = appContext.getSystemService(AccessibilityManager::class.java)
    private val subscriptions = mutableListOf<Cancelable>()
    private val accessibilityPins = mutableMapOf<String, View>()
    private var pinFeatures = emptyMap<String, Feature>()
    private var pinQuery: Cancelable? = null
    private var streetQuery: Cancelable? = null
    private var tapQuery: Cancelable? = null
    private var countries: List<CountryPolygon> = emptyList()
    private var entries: List<WorldPlaceEntry> = emptyList()
    private var byId: Map<String, WorldPlaceEntry> = emptyMap()
    private var visits: Set<String> = emptySet()
    private var selectedId = initialSnapshot?.selected.orEmpty()
    private var selectionRingZoom = initialSnapshot?.takeIf { it.selectionRingVisible }?.zoom
    private var focusingSelection = false
    private var summaryOpen = initialSnapshot?.summaryOpen == true
    private var summaryHeight = 0
    private var stageWidth = 0
    private var stageHeight = 0
    private var overviewZoom = 0.9
    private var motionEnabled = true
    private var style: Style? = null
    private var styleGeneration = 0
    private var styleRequested = false
    private var destroyed = false
    private var started = false
    private var observingNetwork = false
    private var networkAvailable = hasNetwork()
    private var pendingFocus: WorldFocus? = null
    private var lastAppliedFocusToken = 0L
    private var cameraTarget: CameraOptions? = null
    private var animationSequence = 0L
    private var touchingMap = false
    private var tapPoint: ScreenCoordinate? = null
    private var streetReady = false
    private var streetReadyCamera: CameraState? = null
    private var streetBasemapRevealed = false
    private var streetRequestStarted = 0L
    private var streetProviderFailed = false
    private var retryAtMs = 0L
    private var satelliteVisible = true
    private var localEarthLoaded = false
    private var artwork: Map<String, Bitmap> = emptyMap()
    private var firstFrameSubscription: Cancelable? = null
    private var exposeAccessiblePins = false

    val mapView: MapView
    val lightingView: GlobeLightingView
    private val map: MapboxMap
    private val mapTexture: WorldMapTexture?

    var ready by mutableStateOf(false)
        private set
    var hasVisual by mutableStateOf(false)
        private set
    var isMoving by mutableStateOf(false)
        private set
    var hudState by mutableStateOf(WorldHudState())
        private set
    var activeGlobeBounds by mutableStateOf<GlobeBounds?>(null)
        private set
    var onTapEvent: ((WorldTapEvent) -> Unit)? = null

    private val settleCamera = Runnable { onCameraSettled() }
    private val checkStreet = Runnable { checkStreetCoverage() }
    private val loadingTicker = Runnable { refreshLoading() }
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { updateAccessiblePins() }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            if (destroyed) return
            mapTexture?.releaseDetachedTexture()
            mapView.onLowMemory()
        }

        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            ) onLowMemory()
        }
    }
    private val networkListener = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { mainHandler.post(::networkChanged) }
        override fun onLost(network: Network) { mainHandler.post(::networkChanged) }
    }

    init {
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        WorldTilePipeline.configureMapboxTileTransport(appContext)
        val options = MapOptions.Builder().applyDefaultParams(context).constrainMode(ConstrainMode.NONE).build()
        // This view belongs to RoomBrowseState and can outlive a navigation entry.
        val plugins = MapInitOptions.defaultPluginList.filterNot { it.id == Plugin.MAPBOX_LIFECYCLE_PLUGIN_ID }
        mapView = MapView(context, MapInitOptions(context, mapOptions = options, plugins = plugins, styleUri = null, textureView = true))
        lightingView = GlobeLightingView(context)
        mapTexture = WorldMapTexture.retainIn(mapView)
        map = mapView.mapboxMap
        mapView.scalebar.enabled = false
        mapView.compass.enabled = false
        positionMapCredits()
        mapView.gestures.updateSettings {
            rotateEnabled = false
            pitchEnabled = false
            quickZoomEnabled = false
            doubleTouchToZoomOutEnabled = false
            scrollDecelerationEnabled = false
            pinchToZoomDecelerationEnabled = false
            rotateDecelerationEnabled = false
            simultaneousRotateAndPinchToZoomEnabled = false
        }
        mapView.gestures.getGesturesManager().moveGestureDetector.moveThreshold = 1.5f * density
        mapView.gestures.addOnMapClickListener {
            tapPoint?.let(::queryTap)
            true
        }
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchingMap = true
                    tapPoint = null
                    cancelCameraAnimation()
                    mainHandler.removeCallbacks(settleCamera)
                }
                MotionEvent.ACTION_UP -> {
                    touchingMap = false
                    isMoving = false
                    tapPoint = ScreenCoordinate(event.x.toDouble(), event.y.toDouble())
                    scheduleSettle()
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchingMap = false
                    isMoving = false
                    scheduleSettle()
                }
            }
            false
        }
        map.setBounds(CameraBoundsOptions.Builder().minZoom(0.0).maxZoom(18.0).minPitch(0.0).maxPitch(0.0).build())
        subscriptions += map.subscribeCameraChanged {
            if (destroyed || !started) return@subscribeCameraChanged
            syncGlobeLighting(it.cameraState)
        }
        subscriptions += map.subscribeCameraChangedCoalesced {
            if (destroyed || !started) return@subscribeCameraChangedCoalesced
            // Present moving map frames even when the Compose overlays do not change.
            mapView.postInvalidateOnAnimation()
            if (touchingMap || cameraTarget != null) isMoving = true
            syncHud(it.cameraState)
            if (!touchingMap && cameraTarget == null) scheduleSettle()
        }
        firstFrameSubscription = map.subscribeRenderFrameFinished {
            if (!hasVisual && ready && (localEarthLoaded || !satelliteVisible)) {
                hasVisual = true
                syncGlobeLighting(map.cameraState)
                firstFrameSubscription?.cancel()
                firstFrameSubscription = null
                flushPendingFocus()
                scheduleStreetCheck()
                refreshAccessiblePins()
            }
        }
        subscriptions += map.subscribeSourceDataLoaded {
            if (destroyed) return@subscribeSourceDataLoaded
            when (it.sourceId) {
                "earth-base", "earth" -> if (it.type == SourceDataLoadedType.TILE) localEarthLoaded = true
                "streets" -> if (hudState.zoom >= StreetZoom) scheduleStreetCheck(170)
                "earth-hd" -> if (hudState.loadingVisible) refreshLoading()
                WorldMapboxPins.PLACES, WorldMapboxPins.SELECTION ->
                    if (!isMoving && hasVisual) refreshAccessiblePins()
            }
        }
        subscriptions += map.subscribeMapLoadingError {
            if (it.type == MapLoadingErrorType.STYLE ||
                it.sourceId == "earth-hd" || (it.sourceId == "streets" && networkAvailable && !streetProviderFailed)
            ) {
                Log.d("WorldMap", "Mapbox loading error: ${it.type}, source=${it.sourceId.orEmpty()}")
            }
            if (it.sourceId == "streets" && hudState.zoom >= StreetZoom) {
                streetProviderFailed = true
                scheduleStreetCheck()
            }
            if (it.sourceId == "earth-hd") refreshLoading()
            if (it.type == MapLoadingErrorType.STYLE) {
                styleRequested = false
                hudState = hudState.copy(loadingVisible = true, loadingError = true, loadingText = "地图暂不可用 · 重试")
            }
        }
        subscriptions += map.subscribeMapIdle {
            if (!touchingMap && cameraTarget == null) onCameraSettled()
        }
        scope.launch {
            countries = withContext(Dispatchers.Default) { WorldCountryLookup.load(appContext) }
            updateRegionAndCityLabels()
        }
        mapView.doOnLayout { layout ->
            layout.post { onViewportSized(layout.width, layout.height) }
        }
        appContext.registerComponentCallbacks(memoryCallbacks)
    }

    fun onViewportSized(widthPx: Int, heightPx: Int) {
        if (destroyed || widthPx <= 0 || heightPx <= 0) return
        if (stageWidth == widthPx && stageHeight == heightPx && styleRequested) return
        stageWidth = widthPx
        stageHeight = heightPx
        // Mapbox's globe keeps the same screen scale as the camera latitude changes.
        overviewZoom = (WorldMapMath.globeEquatorialZoom(widthPx / density.toDouble(), heightPx / density.toDouble()) -
            MapboxGlobeZoomOffset).coerceAtLeast(0.0)
        if (!styleRequested && mapView.width > 0 && mapView.height > 0) {
            val lat = WorldMapMath.clampLatitude(initialSnapshot?.latitude ?: InitialWorldLatitude)
            val lon = initialSnapshot?.longitude ?: InitialWorldLongitude
            val zoom = (initialSnapshot?.zoom ?: overviewZoom).coerceIn(overviewZoom, 18.0)
            map.setBounds(CameraBoundsOptions.Builder().minZoom(overviewZoom).build())
            loadStyle(cameraOptions(lat, lon, zoom))
        } else if (ready) {
            map.setBounds(CameraBoundsOptions.Builder().minZoom(overviewZoom).build())
            val camera = map.cameraState
            val zoom = if (hudState.enlarged) camera.zoom else overviewZoom
            map.setCamera(cameraOptions(camera.center.latitude(), camera.center.longitude(), zoom))
            syncHud(map.cameraState)
            scheduleStreetCheck()
        }
    }

    private fun loadStyle(camera: CameraOptions) {
        val generation = ++styleGeneration
        styleRequested = true
        style = null
        streetReady = false
        streetReadyCamera = null
        streetBasemapRevealed = false
        streetProviderFailed = false
        satelliteVisible = true
        if (artwork.isEmpty()) artwork = WorldMapboxPins.createArtwork(density)
        map.setCamera(camera)
        map.loadStyle(WorldTilePipeline.buildWorldStyleJson()) { loaded ->
            if (destroyed || generation != styleGeneration) return@loadStyle
            style = loaded
            WorldMapboxPins.installArtwork(loaded, artwork, density)
            WorldMapboxPins.updateData(loaded, entries, selectedId, visits)
            WorldMapboxPins.setSelectionRingVisible(loaded, selectionRingZoom != null)
            ready = true
            // Bind the tile cover to the measured viewport and the restored camera.
            map.setCamera(camera)
            map.triggerRepaint()
            syncHud(map.cameraState)
            scheduleStreetCheck()
            flushPendingFocus()
        }
    }

    private fun bottomPadding(zoom: Double): Int {
        if (summaryOpen && summaryHeight > 0) return summaryHeight
        if (zoom >= 4.0) return 0
        return WorldMapMath.baseBottomPaddingPx(stageWidth, stageHeight)
    }

    private fun cameraOptions(lat: Double, lon: Double, zoom: Double): CameraOptions {
        val latitude = WorldMapMath.clampLatitude(lat)
        val level = zoom.coerceIn(0.0, 18.0)
        return CameraOptions.Builder()
            .center(Point.fromLngLat(WorldMapMath.wrapLongitude(lon), latitude))
            .zoom(level)
            .pitch(0.0)
            .bearing(0.0)
            .padding(EdgeInsets(0.0, 0.0, bottomPadding(level).toDouble(), 0.0))
            .build()
    }

    fun syncData(
        entries: List<WorldPlaceEntry>,
        selection: WorldSelection?,
        focus: WorldFocus?,
        summaryHeightPx: Int,
        visits: Set<String>,
        motion: Boolean,
    ) {
        val nextSelected = selection?.placeId?.takeIf { id -> entries.any { it.place.id == id } }.orEmpty()
        val nextSummary = nextSelected.isNotEmpty() && selection?.summaryOpen == true
        val dataChanged = this.entries !== entries || this.visits != visits || selectedId != nextSelected
        val paddingChanged = summaryOpen != nextSummary || summaryHeight != summaryHeightPx
        this.entries = entries
        if (dataChanged) byId = entries.associateBy { it.place.id }
        this.visits = visits
        if (selectedId != nextSelected) updateSelectionRing(null)
        selectedId = nextSelected
        summaryOpen = nextSummary
        summaryHeight = summaryHeightPx
        if (paddingChanged) positionMapCredits()
        if (motionEnabled && !motion) cameraTarget?.let { target ->
            val completingSelection = focusingSelection
            cancelCameraAnimation()
            map.setCamera(target)
            if (completingSelection && selectionRingZoom != null) selectionRingZoom = map.cameraState.zoom
        }
        motionEnabled = motion
        if (focus != null && focus.token > lastAppliedFocusToken) pendingFocus = focus
        if (dataChanged) style?.let { WorldMapboxPins.updateData(it, entries, selectedId, visits) }
        if (paddingChanged && ready && pendingFocus == null) {
            val camera = map.cameraState
            val wanted = bottomPadding(camera.zoom).toDouble()
            if (abs(wanted - camera.padding.bottom) > 0.5) {
                moveCamera(camera.center.latitude(), camera.center.longitude(), camera.zoom, 220)
            }
        }
        flushPendingFocus()
        if (ready) persistCamera()
    }

    private fun flushPendingFocus() {
        val focus = pendingFocus ?: return
        if (!ready || !hasVisual) return
        val place = byId[focus.placeId]?.place ?: return
        pendingFocus = null
        lastAppliedFocusToken = focus.token
        val camera = map.cameraState
        val zoom = when (focus.kind) {
            WorldFocusKind.Pin -> camera.zoom
            WorldFocusKind.List -> max(11.0, camera.zoom)
            WorldFocusKind.Street -> 15.0
        }.coerceIn(overviewZoom, 18.0)
        moveCamera(
            place.latitude, place.longitude, zoom,
            if (focus.kind == WorldFocusKind.Pin) 360 else 750,
            fly = focus.kind != WorldFocusKind.Pin,
            focusSelection = true,
        )
    }

    private fun positionMapCredits() {
        val bottom = if (summaryOpen && summaryHeight > 0) summaryHeight + 8 * density else 54 * density
        mapView.logo.updateSettings { marginBottom = bottom }
        mapView.attribution.updateSettings { marginBottom = bottom }
    }

    private fun moveCamera(
        lat: Double, lon: Double, zoom: Double, duration: Int, fly: Boolean = false, focusSelection: Boolean = false,
    ) {
        if (destroyed || !ready) return
        val options = cameraOptions(lat, lon, zoom)
        cancelCameraAnimation()
        if (focusSelection) {
            focusingSelection = true
            updateSelectionRing(map.cameraState.zoom)
        }
        if (!motionEnabled || duration <= 0) {
            map.setCamera(options)
            finishSelectionFocus()
            syncHud(map.cameraState)
            onCameraSettled()
            return
        }
        val sequence = ++animationSequence
        cameraTarget = options
        isMoving = true
        val settings = MapAnimationOptions.mapAnimationOptions { this.duration(duration.toLong()) }
        val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (sequence != animationSequence) return
                cameraTarget = null
                finishSelectionFocus()
                onCameraSettled()
            }
        }
        if (fly) map.flyTo(options, settings, listener) else map.easeTo(options, settings, listener)
    }

    private fun cancelCameraAnimation() {
        animationSequence++
        finishSelectionFocus()
        if (cameraTarget != null) {
            cameraTarget = null
            mapView.camera.cancelAllAnimators()
        }
    }

    private fun finishSelectionFocus() {
        if (!focusingSelection) return
        focusingSelection = false
        if (selectionRingZoom != null) selectionRingZoom = map.cameraState.zoom
    }

    private fun updateSelectionRing(zoom: Double?) {
        val visibilityChanged = (selectionRingZoom == null) != (zoom == null)
        selectionRingZoom = zoom
        if (visibilityChanged) style?.let { WorldMapboxPins.setSelectionRingVisible(it, zoom != null) }
    }

    fun zoomBy(delta: Double) = zoomTo(map.cameraState.zoom + delta, 280)

    fun zoomTo(targetZoom: Double, durationMs: Int = 450) {
        val camera = map.cameraState
        val latitude = camera.center.latitude()
        moveCamera(latitude, camera.center.longitude(), targetZoom.coerceIn(overviewZoom, 18.0),
            durationMs, fly = durationMs > 400)
    }

    fun goGlobe() {
        val center = map.cameraState.center
        moveCamera(center.latitude(), center.longitude(), overviewZoom, 800, fly = true)
    }

    fun handleBack(): Boolean {
        if (!ready || !hudState.enlarged) return false
        goGlobe()
        return true
    }

    fun retryTiles() {
        networkChanged()
        if (!networkAvailable && hudState.zoom >= StreetZoom) return
        retryAtMs = System.currentTimeMillis()
        WorldTilePipeline.resetErrorFlags()
        cancelCameraAnimation()
        val camera = map.cameraState
        streetRequestStarted = SystemClock.uptimeMillis()
        streetProviderFailed = false
        loadStyle(cameraOptions(camera.center.latitude(), camera.center.longitude(), camera.zoom))
        refreshLoading()
    }

    private fun scheduleSettle() {
        mainHandler.removeCallbacks(settleCamera)
        mainHandler.postDelayed(settleCamera, 120)
    }

    private fun onCameraSettled() {
        if (!ready || !started || destroyed || touchingMap || cameraTarget != null) return
        mainHandler.removeCallbacks(settleCamera)
        isMoving = false
        syncHud(map.cameraState)
        updateRegionAndCityLabels()
        persistCamera()
        scheduleStreetCheck()
        refreshAccessiblePins()
    }

    private fun syncHud(camera: CameraState) {
        if (stageWidth <= 0 || stageHeight <= 0) return
        syncGlobeLighting(camera)
        val zoom = camera.zoom
        if (!focusingSelection) selectionRingZoom?.let { previous ->
            if (zoom < previous - 0.025) updateSelectionRing(null)
            else if (zoom > previous) selectionRingZoom = zoom
        }
        val beyondGlobe = zoom >= 4.0
        val globeY = (stageHeight - camera.padding.bottom.toFloat()) / 2f
        if (!beyondGlobe && zoom == hudState.zoom && overviewZoom == hudState.globeZoom &&
            activeGlobeBounds?.x == stageWidth / 2f && activeGlobeBounds?.y == globeY
        ) return
        val scale = if (beyondGlobe && !summaryOpen) {
            WorldMapMath.scaleBarMetric(zoom, camera.center.latitude(), density)
        } else null
        val previousStreet = hudState.zoom >= StreetZoom
        val onStreet = zoom >= StreetZoom
        if (onStreet && (!previousStreet || streetCameraChanged(camera))) {
            streetReady = false
            if (streetRequestStarted == 0L) streetRequestStarted = SystemClock.uptimeMillis()
        } else if (!onStreet) {
            streetReady = false
            streetReadyCamera = null
            streetRequestStarted = 0
            streetBasemapRevealed = false
        }
        hudState = hudState.copy(
            zoom = zoom, globeZoom = overviewZoom,
            compactHeader = zoom >= 3.0, enlarged = zoom > overviewZoom + 0.08,
            beyondGlobe = beyondGlobe, satelliteScale = zoom < StreetZoom,
            scaleLabel = scale?.first.orEmpty(), scaleWidthPx = scale?.second ?: 0f, levelLabel = WorldMapMath.levelLabel(zoom),
        )
        activeGlobeBounds = if (zoom < 4.0) WorldMapMath.globeBounds(
            zoom + MapboxGlobeZoomOffset, 0.0,
            stageWidth / 2f, globeY, stageHeight.toFloat(), density,
        ) else null
        updateSatelliteVisibility(onStreet)
        if (previousStreet != onStreet) {
            refreshLoading()
            scheduleStreetCheck()
        }
    }

    private fun streetCameraChanged(camera: CameraState): Boolean {
        val previous = streetReadyCamera ?: return true
        if (abs(camera.zoom - previous.zoom) >= 0.7) return true
        val worldPixels = 512.0 * 2.0.pow(camera.zoom) * density
        val limit = min(stageWidth, stageHeight) * 0.38
        val lonDelta = WorldMapMath.wrapLongitude(camera.center.longitude() - previous.center.longitude())
        val latitudeScale = cos(Math.toRadians(camera.center.latitude())).coerceAtLeast(0.15)
        val longitudePixels = abs(lonDelta) / 360 * worldPixels
        val latitudePixels = abs(camera.center.latitude() - previous.center.latitude()) /
            (360 * latitudeScale) * worldPixels
        return max(longitudePixels, latitudePixels) > limit
    }

    private fun updateSatelliteVisibility(onStreet: Boolean) {
        val currentStyle = style ?: return
        val visible = !onStreet || (networkAvailable && !streetBasemapRevealed)
        if (visible == satelliteVisible) return
        satelliteVisible = visible
        val visibility = Value.valueOf(if (visible) "visible" else "none")
        currentStyle.setStyleLayerProperty("earth-base", "visibility", visibility)
        currentStyle.setStyleLayerProperty("earth", "visibility", visibility)
    }

    private fun syncGlobeLighting(camera: CameraState) {
        if (stageWidth <= 0 || stageHeight <= 0) return
        val zoom = camera.zoom
        val globeY = (stageHeight - camera.padding.bottom.toFloat()) / 2f
        val overviewY = (stageHeight - WorldMapMath.baseBottomPaddingPx(stageWidth, stageHeight)) / 2f
        val bounds = if (zoom - overviewZoom < 0.25) WorldMapMath.globeBounds(
            zoom + MapboxGlobeZoomOffset, 0.0,
            stageWidth / 2f, globeY, stageHeight.toFloat(), density,
        ) else null
        lightingView.updateState(hasVisual, bounds, zoom, overviewZoom, overviewY)
    }

    private fun scheduleStreetCheck(delay: Long = 90) {
        if (hudState.zoom < StreetZoom || destroyed || !started) return
        mainHandler.removeCallbacks(checkStreet)
        mainHandler.postDelayed(checkStreet, delay)
    }

    private fun checkStreetCoverage() {
        if (destroyed || !ready || !hasVisual || hudState.zoom < StreetZoom || touchingMap) return
        val requestedAt = map.cameraState
        streetQuery?.cancel()
        streetQuery = map.queryRenderedFeatures(
            RenderedQueryGeometry(visibleStage()),
            RenderedQueryOptions(StreetLayers, null),
        ) { result ->
            if (destroyed || hudState.zoom < StreetZoom || map.cameraState != requestedAt) return@queryRenderedFeatures
            streetReady = result.value?.isNotEmpty() == true
            streetReadyCamera = requestedAt.takeIf { streetReady }
            if (streetReady) {
                streetBasemapRevealed = true
                streetRequestStarted = 0
                streetProviderFailed = false
            } else if (streetRequestStarted == 0L) {
                streetRequestStarted = SystemClock.uptimeMillis()
            }
            updateSatelliteVisibility(true)
            refreshLoading()
        }
    }

    private fun refreshLoading() {
        if (destroyed || !styleRequested) return
        mainHandler.removeCallbacks(loadingTicker)
        val onStreet = hudState.zoom >= StreetZoom
        val offline = onStreet && !networkAvailable && !streetReady && hasVisual
        val elapsed = if (streetRequestStarted == 0L) 0L else SystemClock.uptimeMillis() - streetRequestStarted
        val streetPending = onStreet && !streetReady && networkAvailable
        val streetError = streetPending && (streetProviderFailed && elapsed >= 1800 || elapsed >= 6500)
        val hdError = !onStreet && hudState.zoom >= 1.6 && networkAvailable &&
            WorldTilePipeline.recentHdErrorSince(retryAtMs)
        if (streetPending && !streetError && started) {
            mainHandler.postDelayed(loadingTicker, 180)
            if (elapsed in 180..1700 && !isMoving && !touchingMap) scheduleStreetCheck(90)
        }
        hudState = hudState.copy(
            streetOffline = offline,
            loadingVisible = !offline && (hdError || streetError || (streetPending && elapsed >= 650)),
            progressVisible = streetPending && !streetError && elapsed >= 260,
            loadingError = hdError || streetError,
            loadingText = when {
                hdError -> "高清地球暂不可用 · 重试"
                streetError -> "街道暂不可用 · 重试"
                else -> "正在加载街道"
            },
        )
    }

    private fun hasNetwork(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun networkChanged() {
        if (destroyed) return
        val available = hasNetwork()
        if (available != networkAvailable) {
            networkAvailable = available
            streetProviderFailed = false
            if (available && hudState.zoom >= StreetZoom) {
                streetRequestStarted = SystemClock.uptimeMillis()
                map.triggerRepaint()
            }
            updateSatelliteVisibility(hudState.zoom >= StreetZoom)
            scheduleStreetCheck()
        }
        refreshLoading()
    }

    private fun visibleStage(): ScreenBox {
        val exposedHeight = (stageHeight - if (summaryOpen) summaryHeight else 0).coerceAtLeast(1)
        return ScreenBox(ScreenCoordinate(0.0, 0.0), ScreenCoordinate(stageWidth.toDouble(), exposedHeight.toDouble()))
    }

    private fun queryTap(point: ScreenCoordinate) {
        if (!ready || destroyed || hudState.streetOffline) return
        val radius = 7.0 * density
        val hitArea = ScreenBox(
            ScreenCoordinate(point.x - radius, point.y - radius),
            ScreenCoordinate(point.x + radius, point.y + radius),
        )
        tapQuery?.cancel()
        tapQuery = map.queryRenderedFeatures(
            RenderedQueryGeometry(hitArea),
            RenderedQueryOptions(WorldMapboxPins.layers, null),
        ) { result ->
            if (destroyed || touchingMap) return@queryRenderedFeatures
            val features = result.value.orEmpty().map { it.queriedFeature.feature }
            val first = features.firstOrNull()
            val placeIds = features.mapNotNull { it.getStringProperty("id") }.distinct()
            when {
                map.cameraState.zoom >= 17.8 && placeIds.size > 1 ->
                    onTapEvent?.invoke(WorldTapEvent.ChoosePlaces(placeIds))
                first != null -> selectFeature(first)
                summaryOpen && selectedId.isNotEmpty() ->
                    onTapEvent?.invoke(WorldTapEvent.DismissSummary(selectedId))
            }
        }
    }

    private fun selectFeature(feature: Feature) {
        if (!feature.hasProperty("cluster_id")) {
            feature.getStringProperty("id")?.let { onTapEvent?.invoke(WorldTapEvent.SelectPlace(it)) }
            return
        }
        val point = feature.geometry() as? Point ?: return
        map.getGeoJsonClusterExpansionZoom(WorldMapboxPins.PLACES, feature) { result ->
            if (destroyed) return@getGeoJsonClusterExpansionZoom
            val nextZoom = (result.value?.value?.contents as? Number)?.toDouble()?.plus(0.35)
                ?: (map.cameraState.zoom + 2.0)
            if (nextZoom < 18.0) {
                moveCamera(point.latitude(), point.longitude(), max(nextZoom, map.cameraState.zoom + 0.8), 550, fly = true)
            } else {
                map.getGeoJsonClusterLeaves(WorldMapboxPins.PLACES, feature, entries.size.toLong(), 0) { leaves ->
                    if (destroyed) return@getGeoJsonClusterLeaves
                    val ids = leaves.value?.featureCollection.orEmpty().mapNotNull { it.getStringProperty("id") }.distinct()
                    when {
                        ids.size > 1 -> onTapEvent?.invoke(WorldTapEvent.ChoosePlaces(ids))
                        ids.size == 1 -> onTapEvent?.invoke(WorldTapEvent.SelectPlace(ids.first()))
                    }
                }
            }
        }
    }

    private fun refreshAccessiblePins() {
        if (destroyed || !started || !ready || !hasVisual || isMoving || hudState.streetOffline) return
        if (!exposeAccessiblePins && !BuildConfig.DEBUG) return
        pinQuery?.cancel()
        pinQuery = map.queryRenderedFeatures(
            RenderedQueryGeometry(visibleStage()),
            RenderedQueryOptions(WorldMapboxPins.layers, null),
        ) { result ->
            if (destroyed || !started || isMoving || hudState.streetOffline) return@queryRenderedFeatures
            val features = result.value.orEmpty().map { it.queriedFeature.feature }
            val visible = features.mapNotNull { feature ->
                val id = when {
                    feature.hasProperty("id") -> feature.getStringProperty("id")
                    feature.hasProperty("cluster_id") -> "cluster-${feature.getNumberProperty("cluster_id")}"
                    else -> null
                } ?: return@mapNotNull null
                id to feature
            }.toMap()
            pinFeatures = visible
            if (!exposeAccessiblePins) return@queryRenderedFeatures
            for (key in accessibilityPins.keys - visible.keys) {
                accessibilityPins.remove(key)?.let(mapView.viewAnnotationManager::removeViewAnnotation)
            }
            for ((key, feature) in visible) {
                if (key in accessibilityPins) continue
                val point = feature.geometry() as? Point ?: continue
                val label = if (feature.hasProperty("name")) feature.getStringProperty("name")
                    else "${feature.getNumberProperty("point_count").toInt()} 个地点"
                val size = (44 * density).roundToInt()
                val node = View(mapView.context).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size)
                    contentDescription = label
                    isFocusable = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                ViewCompat.replaceAccessibilityAction(
                    node, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, label,
                ) { _, _ -> pinFeatures[key]?.let(::selectFeature) != null }
                val options = ViewAnnotationOptions.Builder()
                    .annotatedFeature(AnnotatedFeature(point))
                    .width(size.toDouble()).height(size.toDouble()).allowOverlap(true)
                    .build()
                mapView.viewAnnotationManager.addViewAnnotation(node, options)
                accessibilityPins[key] = node
            }
        }
    }

    private fun updateAccessiblePins() {
        if (destroyed || !started) return
        val enabled = accessibility?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty().isNotEmpty()
        if (enabled == exposeAccessiblePins) return
        exposeAccessiblePins = enabled
        if (enabled) {
            refreshAccessiblePins()
        } else {
            accessibilityPins.values.forEach(mapView.viewAnnotationManager::removeViewAnnotation)
            accessibilityPins.clear()
        }
    }

    private fun updateRegionAndCityLabels() {
        if (!ready) return
        if (!hudState.compactHeader) {
            if (hudState.regionName.isNotEmpty() || hudState.cityName.isNotEmpty()) {
                hudState = hudState.copy(regionName = "", cityName = "")
            }
            return
        }
        val camera = map.cameraState
        val lat = camera.center.latitude()
        val lon = camera.center.longitude()
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.087)
        var nearest: WorldPlaceEntry? = null
        var minSquaredDistance = Double.POSITIVE_INFINITY
        for (entry in entries) {
            val dLat = entry.place.latitude - lat
            val dLon = WorldMapMath.wrapLongitude(entry.place.longitude - lon) * cosLat
            val squaredDistance = dLat * dLat + dLon * dLon
            if (squaredDistance < minSquaredDistance) {
                minSquaredDistance = squaredDistance
                nearest = entry
            }
        }
        val visibleCity = nearest?.takeIf { camera.zoom >= 7.0 && minSquaredDistance < 0.04 }
        val country = visibleCity?.let { canonicalRegionName(it.place.country) }
            ?: WorldCountryLookup.countryAt(countries, lon, lat)
        val city = visibleCity?.let { unifiedDestinationRegion(country) ?: it.place.city }.orEmpty()
        hudState = hudState.copy(
            regionName = country.takeIf { hudState.compactHeader }.orEmpty(),
            cityName = city.takeIf { it != country }.orEmpty(),
        )
    }

    private fun persistCamera() {
        if (!ready) return
        val current = map.cameraState
        browse.world(WorldCameraSnapshot(
            WorldMapMath.wrapLongitude(current.center.longitude()), current.center.latitude(), current.zoom,
            selectedId, summaryOpen, selectionRingZoom != null,
        ))
    }

    fun screenToLatLng(xPx: Float, yPx: Float): Pair<Double, Double>? {
        val result = map.coordinateInfoForPixel(ScreenCoordinate(xPx.toDouble(), yPx.toDouble()))
        if (!result.isOnSurface) return null
        return result.coordinate.latitude() to result.coordinate.longitude()
    }

    fun jumpTo(lat: Double, lon: Double, zoom: Double) = moveCamera(lat, lon, zoom, 0)

    fun hasPendingCameraTarget(): Boolean = cameraTarget != null

    fun diagnostics(): WorldDiagnosticsSnapshot {
        val camera = map.cameraState
        return WorldDiagnosticsSnapshot(
            ready, hasVisual, isMoving, camera.center.longitude(), camera.center.latitude(), camera.zoom,
            selectedId, summaryOpen, entries.size, pinFeatures.size, WorldMapMath.globenessForZoom(camera.zoom),
        )
    }

    fun onResume() {
        if (destroyed || started) return
        started = true
        mapView.onStart()
        if (!observingNetwork) {
            connectivity?.registerDefaultNetworkCallback(networkListener)
            observingNetwork = connectivity != null
        }
        accessibility?.addAccessibilityStateChangeListener(accessibilityListener)
        updateAccessiblePins()
        networkChanged()
        if (ready) {
            syncHud(map.cameraState)
            map.triggerRepaint()
        }
    }

    fun onPause() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(loadingTicker)
        mainHandler.removeCallbacks(settleCamera)
        mainHandler.removeCallbacks(checkStreet)
        accessibility?.removeAccessibilityStateChangeListener(accessibilityListener)
        if (observingNetwork) connectivity?.unregisterNetworkCallback(networkListener)
        observingNetwork = false
        touchingMap = false
        tapPoint = null
        isMoving = false
        cancelCameraAnimation()
        tapQuery?.cancel()
        pinQuery?.cancel()
        streetQuery?.cancel()
        persistCamera()
        mapView.onStop()
    }

    fun destroy() {
        if (destroyed) return
        onPause()
        destroyed = true
        appContext.unregisterComponentCallbacks(memoryCallbacks)
        onTapEvent = null
        subscriptions.forEach(Cancelable::cancel)
        firstFrameSubscription?.cancel()
        pinQuery?.cancel()
        streetQuery?.cancel()
        tapQuery?.cancel()
        scope.cancel()
        mapTexture?.close()
        lightingView.release()
        mapView.onDestroy()
        artwork.values.forEach(Bitmap::recycle)
        artwork = emptyMap()
    }

    private companion object {
        const val StreetZoom = 8.0
        val StreetLayers = listOf(
            "world-road", "world-path", "world-rail", "world-building", "world-water", "world-waterway", "world-landuse",
        )
    }
}
