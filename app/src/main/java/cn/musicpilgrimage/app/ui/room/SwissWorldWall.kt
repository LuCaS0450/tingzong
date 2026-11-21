package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.data.Catalog
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.musicMapCities
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.LocalRouteIsCurrent

/**
 * Pure native World wall powered by MapLibre Native (`TextureView`) + Compose overlays.
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
        if (active && controller == null) {
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
                            Lifecycle.Event.ON_START -> ctrl.onStart()
                            Lifecycle.Event.ON_RESUME -> ctrl.onResume()
                            Lifecycle.Event.ON_PAUSE -> ctrl.onPause()
                            Lifecycle.Event.ON_STOP -> ctrl.onStop()
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
                        ctrl.onStop()
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
                        }
                    },
                )
                WorldPinOverlay(
                    controller = ctrl,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val compact by remember { derivedStateOf { controller?.hudState?.compactHeader == true } }
        val initialHud = remember { WorldHudState() }

        WorldHudOverlay(
            readHud = { controller?.hudState ?: initialHud },
            cityCount = cities.size,
            placeCount = entries.size,
            summaryOpen = summary != null,
            interactive = active,
            unit = unit,
            onGoGlobe = { controller?.goGlobe() },
            onZoomRegion = { controller?.zoomTo(5.0, durationMs = 600) },
            onZoomCity = { controller?.zoomTo(11.0, durationMs = 600) },
            onZoomBy = { delta -> controller?.zoomBy(delta) },
            onZoomToStop = { stopZoom ->
                if (stopZoom <= 0.5) controller?.goGlobe()
                else controller?.zoomTo(stopZoom, durationMs = 650)
            },
            onTrips = onTrips,
            onFootprints = onFootprints,
            onRetryTiles = { controller?.retryTiles() },
        )

        SwissTextButton(
            { showChooser(emptyList()) },
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = unit * if (compact) 5 else 83)
                .padding(end = unit * if (compact) 57 else 22)
                .heightIn(min = 48.dp)
                .testTag("world-place-list"),
            enabled = active,
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null, Modifier.size(18.dp), tint = RoomColors.Ink)
            Spacer(Modifier.width(5.dp))
            Text("地点", color = RoomColors.Ink, fontWeight = FontWeight.Medium)
        }
        IconButton(
            onSearch,
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = unit * if (compact) 5 else 10)
                .padding(end = unit * 10)
                .testTag("world-search"),
            enabled = active,
        ) {
            Icon(Icons.Outlined.Search, "搜索歌曲与地点", tint = RoomColors.Ink)
        }

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
            onDetail = onPlace,
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
