package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.data.Catalog
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.musicMapCities
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.LocalRouteIsCurrent

/**
 * The native map owns the bottom map and pins; Compose owns the page chrome.
 * Keeps `MapView` alive across background/foreground transitions so returning never reloads.
 */
@Composable
internal fun SwissWorldWall(
    catalog: Catalog,
    browse: RoomBrowseState,
    favorites: Set<String>,
    favoriteBusy: Boolean,
    onPlace: (String) -> Unit,
    onFavorite: (String) -> Unit,
    onTrips: () -> Unit,
    onFootprints: () -> Unit,
    onAdd: (Place) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    visits: Set<String>,
    active: Boolean,
    awaitMapStartup: suspend (Context) -> Unit = {},
) {
    val context = LocalContext.current
    val motion = LocalPhysicalMotion.current
    val isCurrent by rememberUpdatedState(LocalRouteIsCurrent.current)
    val cities = remember(catalog) { musicMapCities(catalog, null) }
    val entries = remember(context, cities) {
        cities.flatMap { city -> city.places.map { worldPlaceEntry(context, it) } }.distinctBy { it.place.id }
    }
    val byId = remember(entries) { entries.associateBy { it.place.id } }
    val selection = browse.worldSelection?.takeIf { it.placeId in byId }
    val summary = selection?.takeIf { it.summaryOpen }?.let { byId[it.placeId] }
    var chooser by remember(browse) { mutableStateOf<List<String>?>(null) }
    var chooserSequence by remember(browse) { mutableIntStateOf(0) }
    var measurement by remember(browse) { mutableStateOf<Pair<String, Int>?>(null) }
    val summaryHeightPx = measurement?.takeIf { it.first == summary?.place?.id }?.second ?: 0
    val focus = browse.worldFocus?.takeIf { it.placeId in byId && (summary == null || summaryHeightPx > 0) }

    var controller by remember(browse) { mutableStateOf(browse.worldController) }
    LaunchedEffect(browse, active) {
        if (active && controller == null && BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()) {
            awaitMapStartup(context)
            controller = browse.worldController ?: NativeWorldController(context, browse).also { browse.worldController = it }
        }
    }

    fun showChooser(ids: List<String>) {
        chooserSequence++
        chooser = ids
    }

    BackHandler(enabled = active) {
        when {
            chooser != null -> chooser = null
            summary != null -> browse.closeWorldSummary()
            controller?.handleBack() == true -> Unit
            else -> onBack()
        }
    }

    LaunchedEffect(active) {
        if (!active) chooser = null
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("world-wall")
    ) {
        val unit = maxWidth / 360

        // Approved textured warm-white canvas shows transparently behind the 3D globe (z < 4).
        RoomShelfWall(Modifier.fillMaxSize())

        if (active && BuildConfig.MAPBOX_ACCESS_TOKEN.isBlank()) {
            Text(
                "地图暂不可用",
                color = RoomColors.Muted,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        controller?.let { ctrl ->
            val currentById by rememberUpdatedState(byId)
            val currentBrowse by rememberUpdatedState(browse)
            val owner = LocalLifecycleOwner.current

            if (active) {
                DisposableEffect(ctrl, owner) {
                    WorldTestRegistry.activeController = ctrl
                    ctrl.onTapEvent = { event ->
                        if (isCurrent()) when (event) {
                            is WorldTapEvent.SelectPlace -> if (event.placeId in currentById) {
                                currentBrowse.focusWorld(event.placeId, WorldFocusKind.Pin)
                            }
                            is WorldTapEvent.ChoosePlaces -> {
                                val filtered = event.placeIds.filter { it in currentById }
                                if (filtered.isNotEmpty()) showChooser(filtered)
                            }
                            is WorldTapEvent.DismissSummary -> {
                                if (currentBrowse.worldSelection?.placeId == event.placeId) {
                                    currentBrowse.closeWorldSummary()
                                }
                            }
                        }
                    }
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> ctrl.onResume()
                            Lifecycle.Event.ON_PAUSE -> ctrl.onPause()
                            else -> Unit
                        }
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose {
                        owner.lifecycle.removeObserver(observer)
                        if (WorldTestRegistry.activeController === ctrl) {
                            WorldTestRegistry.activeController = null
                        }
                        ctrl.onTapEvent = null
                        ctrl.onPause()
                    }
                }
            }

            LaunchedEffect(ctrl, entries, selection, focus, summaryHeightPx, visits, motion, active) {
                if (active) {
                    ctrl.syncData(entries, selection, focus, summaryHeightPx, visits, motion)
                }
            }

            // Stage container starting at 64*unit matches the web `#stage` viewport geometry.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = unit * 64f)
                    .onSizeChanged { size ->
                        ctrl.onViewportSized(size.width, size.height)
                    }
            ) {
                AndroidView(
                    factory = { FrameLayout(it) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.removeAllViews() },
                    update = { host ->
                        if (active) {
                            if (ctrl.mapView.parent !== host) {
                                (ctrl.mapView.parent as? ViewGroup)?.removeView(ctrl.mapView)
                                host.addView(ctrl.mapView)
                            }
                            if (ctrl.lightingView.parent !== host) {
                                (ctrl.lightingView.parent as? ViewGroup)?.removeView(ctrl.lightingView)
                                host.addView(
                                    ctrl.lightingView,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                            } else if (host.indexOfChild(ctrl.lightingView) < host.indexOfChild(ctrl.mapView)) {
                                ctrl.lightingView.bringToFront()
                            }
                        }
                    },
                )
                if (ctrl.hudState.streetOffline) {
                    val summaryInset = with(LocalDensity.current) { summaryHeightPx.toDp() }
                    WorldStreetOffline(
                        onRetry = ctrl::retryTiles,
                        interactive = active,
                        bottomInset = summaryInset,
                    )
                }
            }
        }

        val initialHud = remember { WorldHudState() }

        WorldHudOverlay(
            readHud = { controller?.hudState ?: initialHud },
            summaryOpen = summary != null,
            interactive = active,
            unit = unit,
            onGoGlobe = { controller?.goGlobe() },
            onZoomRegion = { controller?.zoomTo(5.0, durationMs = 650) },
            onZoomCity = { controller?.zoomTo(WorldCityZoom, durationMs = 650) },
            onTrips = onTrips,
            onFootprints = onFootprints,
            onRetryTiles = { controller?.retryTiles() },
            onPlaces = { showChooser(emptyList()) },
            onSearch = onSearch,
        )

        WorldPlaceSummary(
            entry = summary,
            modifier = Modifier.align(Alignment.BottomCenter),
            favorites = favorites,
            favoriteBusy = favoriteBusy,
            interactive = active,
            onDismiss = browse::closeWorldSummary,
            onReopen = browse::reopenWorldSummary,
            onMeasure = { id, height -> measurement = id to height },
            onFavorite = onFavorite,
            onAround = { browse.focusWorld(it, WorldFocusKind.Street) },
            onAdd = onAdd,
            onDetail = { id ->
                browse.holdWorldCameraForDetail()
                onPlace(id)
            },
        )

        chooser?.takeIf { active }?.let { ids ->
            val options = remember(entries, ids) { if (ids.isEmpty()) entries else ids.mapNotNull(byId::get) }
            key(chooserSequence) {
                WorldPlaceChooser(
                    title = if (ids.isEmpty()) "音乐地点" else "此处 ${options.size} 个地点",
                    entries = options,
                    onDismiss = { chooser = null },
                ) { id ->
                    chooser = null
                    browse.focusWorld(id, WorldFocusKind.List)
                }
            }
        }
    }
}

@Composable
private fun WorldStreetOffline(
    onRetry: () -> Unit,
    interactive: Boolean,
    bottomInset: Dp,
) {
    var retried by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .background(RoomColors.Paper)
            .pointerInput(Unit) {
                // Give the failure page the stage's touches while leaving its controls interactive.
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            }
            .padding(start = 24.dp, end = 24.dp, bottom = bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.testTag("world-street-offline").semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "街道地图加载失败",
                color = RoomColors.Ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                if (retried) "当前仍未连接网络。" else "连接网络后重试。",
                color = RoomColors.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            SwissOutlinedButton(
                onClick = { retried = true; onRetry() },
                enabled = interactive,
                modifier = Modifier.heightIn(min = 48.dp).testTag("world-street-offline-retry"),
            ) {
                Text("重试")
            }
        }
    }
}
