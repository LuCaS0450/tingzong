package cn.musicpilgrimage.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.MapSupport
import cn.musicpilgrimage.app.design.MapNetworking
import cn.musicpilgrimage.app.data.displayScene
import cn.musicpilgrimage.app.data.associationLabels
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import cn.musicpilgrimage.app.ui.room.MusicMapPins

internal data class MapPanelAppearance(val surface: Color, val ink: Color, val secondary: Color, val accent: Color)

@Composable
internal fun MapPanel(places: List<Place>, mapKey: String, onPlace: (Place) -> Unit, modifier: Modifier = Modifier,
    selected: Pair<Double,Double>? = null, onCoordinate: ((Double,Double)->Unit)? = null,
    referenceOverviewZoom: Double? = null,
    rasterBaseMap: Boolean = false,
    mapStyleId: String = MapNetworking.referenceMapId,
    rasterHttpClient: OkHttpClient? = MapNetworking.client,
    mediaShape: Shape = MapMediaShape,
    appearance: MapPanelAppearance? = null,
    savedCamera: List<Double>? = null,
    onCameraChanged: ((List<Double>) -> Unit)? = null,
    roomPins:Boolean=false,selectedPlaceId:String?=null,gesturesEnabled:Boolean=true,
    zoomRequest:Int=0,onReady:((Boolean)->Unit)?=null) {
    val context = LocalContext.current
    val panelColor=appearance?.surface ?: Panel
    val textColor=appearance?.ink ?: MaterialTheme.colorScheme.onSurface
    val secondaryColor=appearance?.secondary ?: Muted
    val accentColor=appearance?.accent ?: Acid
    val physicalMotion=LocalPhysicalMotion.current
    val graphicsSupported=remember(context){MapSupport.isAvailable(context)}
    if (mapKey.isBlank() || !graphicsSupported) {
        Surface(modifier.mediaShadow(mediaShape), color = panelColor, shape = mediaShape) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.Center) {
                Text(if(graphicsSupported)"地图暂不可用" else "这台设备暂不支持在线地图", fontSize = 20.sp, color=textColor)
                Spacer(Modifier.height(8.dp))
                Text(if(graphicsSupported)"地图服务尚未配置。地点资料可继续浏览。" else "可以继续使用地点列表和外部地图入口。", color = secondaryColor, lineHeight = 20.sp, fontSize = 13.sp)
            }
        }
        return
    }
    val owner = LocalLifecycleOwner.current
    val currentOnPlace by rememberUpdatedState(onPlace)
    val currentCoordinate by rememberUpdatedState(onCoordinate)
    val currentCamera by rememberUpdatedState(savedCamera)
    val currentCameraChanged by rememberUpdatedState(onCameraChanged)
    val currentReady by rememberUpdatedState(onReady)
    var map by remember(mapKey) { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember(mapKey) { mutableStateOf(false) }
    var cameraPlaced by remember(mapKey){mutableStateOf(false)}
    var firstFrame by remember(mapKey){mutableStateOf(false)}
    var error by remember(mapKey) { mutableStateOf<String?>(null) }
    var retry by remember(mapKey) { mutableIntStateOf(0) }
    var appliedZoom by remember {mutableIntStateOf(zoomRequest)}
    val markers = remember { mutableMapOf<Long, Place>() }
    val mapView = remember(mapKey) {
        MapNetworking.configure(context)
        MapView(context).apply { onCreate(null) }
    }
    var alive by remember(mapView){mutableStateOf(true)}
    DisposableEffect(mapView){alive=true;onDispose{alive=false}}
    val placedNow by rememberUpdatedState(cameraPlaced)
    DisposableEffect(mapView) {
        val rendered=MapView.OnDidFinishRenderingMapListener{fully->if(fully&&placedNow)firstFrame=true}
        mapView.addOnDidFinishRenderingMapListener(rendered)
        onDispose{mapView.removeOnDidFinishRenderingMapListener(rendered)}
    }
    // A map may already be usable while an optional glyph/tile is still pending.
    // Reveal the actual drawn streets instead of waiting for every remote resource.
    LaunchedEffect(map,ready,cameraPlaced,error) {
        val native=map ?: return@LaunchedEffect
        if(!roomPins||!ready||!cameraPlaced||error!=null)return@LaunchedEffect
        while(!firstFrame&&error==null) {
            kotlinx.coroutines.delay(80)
            if(mapView.width>0&&mapView.height>0) {
                val lines=native.style?.layers?.filterIsInstance<org.maplibre.android.style.layers.LineLayer>()?.map{it.id}.orEmpty()
                if(lines.isNotEmpty()&&native.queryRenderedFeatures(android.graphics.RectF(0f,0f,mapView.width.toFloat(),mapView.height.toFloat()),*lines.toTypedArray()).isNotEmpty())firstFrame=true
            }
        }
    }
    DisposableEffect(map,ready) {
        val native=map
        val idle=MapLibreMap.OnCameraIdleListener {
            if(ready&&alive&&placedNow)native?.cameraPosition?.let{camera->camera.target?.let{target->currentCameraChanged?.invoke(listOf(target.latitude,target.longitude,camera.zoom))}}
        }
        val click=MapLibreMap.OnMapClickListener {point->currentCoordinate?.invoke(point.latitude,point.longitude);currentCoordinate!=null}
        native?.addOnCameraIdleListener(idle);native?.addOnMapClickListener(click)
        onDispose{native?.removeOnCameraIdleListener(idle);native?.removeOnMapClickListener(click)}
    }
    val visible=ready&&(!roomPins||(cameraPlaced&&firstFrame))
    LaunchedEffect(visible,error){currentReady?.invoke(visible&&error==null)}
    LaunchedEffect(map,ready,gesturesEnabled) {map?.uiSettings?.apply {
        isScrollGesturesEnabled=gesturesEnabled;isZoomGesturesEnabled=gesturesEnabled
        isDoubleTapGesturesEnabled=gesturesEnabled;isQuickZoomGesturesEnabled=gesturesEnabled
    }}
    LaunchedEffect(map,ready,zoomRequest) {
        val delta=(zoomRequest-appliedZoom).coerceIn(-1,1);appliedZoom=zoomRequest
        val native=map
        if(native!=null&&ready&&delta!=0) {
            val update=CameraUpdateFactory.zoomBy(delta.toDouble())
            if(physicalMotion)native.animateCamera(update,180)else native.moveCamera(update)
        }
    }
    DisposableEffect(mapView, owner) {
        val lifecycle = owner.lifecycle
        var started = false
        var resumed = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { mapView.onStart(); started = true }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { mapView.onResume(); resumed = true }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    map?.cancelTransitions()
                    if(cameraPlaced)map?.cameraPosition?.let{position->position.target?.let{target->currentCameraChanged?.invoke(listOf(target.latitude,target.longitude,position.zoom))}}
                    mapView.onPause(); resumed = false
                }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        val callbacks = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() { mapView.onLowMemory() }
            override fun onTrimMemory(level: Int) { if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory() }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose {
            if(cameraPlaced)map?.cameraPosition?.let { camera -> camera.target?.let { target ->
                currentCameraChanged?.invoke(listOf(target.latitude,target.longitude,camera.zoom))
            } }
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, retry, rasterBaseMap, mapStyleId) {
        ready = false
        cameraPlaced=false
        firstFrame=false
        error = null
        val encodedKey = Uri.encode(mapKey.trim())
        val style = try {
            if (rasterBaseMap) {
                // Check provider metadata explicitly: a failed raster source otherwise
                // leaves an empty native style without the style-load error callback.
                val metadata = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(rasterHttpClient).newCall(Request.Builder()
                        .url("https://api.maptiler.com/maps/${Uri.encode(mapStyleId)}/tiles.json?key=$encodedKey").build())
                        .execute().use { response ->
                            check(response.isSuccessful)
                            JSONObject(requireNotNull(response.body).string())
                        }
                }
                val source = JSONObject().put("type", "raster").put("tileSize", 512)
                    .put("tiles", metadata.getJSONArray("tiles"))
                    .put("attribution", metadata.optString("attribution"))
                    .put("minzoom", metadata.optInt("minzoom", 0)).put("maxzoom", metadata.optInt("maxzoom", 22))
                Style.Builder().fromJson(JSONObject().put("version", 8)
                    .put("sources", JSONObject().put("reference-map", source))
                    .put("layers", JSONArray().put(JSONObject().put("id", "reference-map")
                        .put("type", "raster").put("source", "reference-map"))).toString())
            } else if (rasterHttpClient != null) {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    rasterHttpClient.newCall(Request.Builder()
                        .url("https://api.maptiler.com/maps/${Uri.encode(mapStyleId)}/style.json?key=$encodedKey").build())
                        .execute().use { response ->
                            check(response.isSuccessful)
                            requireNotNull(response.body).string()
                        }
                }
                Style.Builder().fromJson(json)
            } else {
                Style.Builder().fromUri("https://api.maptiler.com/maps/${Uri.encode(mapStyleId)}/style.json?key=$encodedKey")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            error = "地图暂时未能加载，请检查网络或地图配置。"
            return@LaunchedEffect
        }
        // Native map APIs require the Android UI thread, also after metadata IO.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            mapView.getMapAsync { nativeMap ->
                if(!alive)return@getMapAsync
                map = nativeMap
                nativeMap.uiSettings.apply {
                    isAttributionEnabled = false
                    isLogoEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
                nativeMap.setOnMarkerClickListener { marker -> markers[marker.id]?.let { currentOnPlace(it) }; true }
                nativeMap.setStyle(style) {
                    if(!alive)return@setStyle
                    error = null
                    ready = true
                }
            }
        }
    }
    LaunchedEffect(mapKey, retry) {
        kotlinx.coroutines.delay(25_000)
        if ((!ready||(roomPins&&!firstFrame)) && error == null) error = "地图加载时间较长，可重试或切换地点列表。"
    }
    DisposableEffect(mapView) {
        val listener = MapView.OnDidFailLoadingMapListener { _ -> error = "地图暂时未能加载，请检查网络或地图配置。" }
        mapView.addOnDidFailLoadingMapListener(listener)
        onDispose { mapView.removeOnDidFailLoadingMapListener(listener) }
    }
    LaunchedEffect(map, ready, places, selected, referenceOverviewZoom, physicalMotion) {
        val nativeMap = map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        nativeMap.clear()
        markers.clear()
        places.filter { !roomPins && referenceOverviewZoom==null && it.displayScene() == null }.forEach { place ->
            val marker = nativeMap.addMarker(MarkerOptions().position(LatLng(place.latitude, place.longitude)).title(place.name).snippet(place.associationLabels()+" · 发行封面待补"))
            markers[marker.id] = place
        }
        selected?.takeIf {referenceOverviewZoom==null}?.let { nativeMap.addMarker(MarkerOptions().position(LatLng(it.first,it.second)).title("所选位置")) }
        val camera=when {
            currentCamera?.size==3 -> currentCamera!!.let { CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(it[0],it[1])).zoom(it[2]).build()) }
            selected!=null -> CameraUpdateFactory.newLatLngZoom(LatLng(selected.first,selected.second),referenceOverviewZoom ?: nativeMap.cameraPosition.zoom.coerceAtLeast(11.0))
            places.size == 1 -> CameraUpdateFactory.newLatLngZoom(LatLng(places[0].latitude, places[0].longitude), referenceOverviewZoom ?: 13.0)
            places.size > 1 -> {
                val bounds = LatLngBounds.Builder().includes(places.map { LatLng(it.latitude, it.longitude) }).build()
                CameraUpdateFactory.newLatLngBounds(bounds, 55)
            }
            else -> CameraUpdateFactory.newLatLngZoom(LatLng(25.0, 115.0), 2.0)
        }
        if(physicalMotion && currentCamera==null&&!roomPins)nativeMap.animateCamera(camera) else nativeMap.moveCamera(camera)
        cameraPlaced=true
    }
    Box(modifier.mediaShadow(mediaShape).clip(mediaShape).background(panelColor)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Until a style is ready, the native surface has no map content to show.
        // The surrounding material owns the waiting surface; credits remain visible.
        if(appearance != null && (!visible || error != null))
            Box(Modifier.fillMaxSize().background(panelColor).testTag("map-waiting-surface"))
        if (visible && error==null && referenceOverviewZoom==null) map?.let { nativeMap ->
            if(roomPins)MusicMapPins(mapView,nativeMap,places,selectedPlaceId){currentOnPlace(it)}
            else ReferenceMapCards(mapView, nativeMap, places) { currentOnPlace(it) }
        }
        if (!visible && error == null) {
            Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), color = accentColor, strokeWidth = 2.dp)
                if(appearance != null) Text("正在连接地图…",color=textColor,fontSize=14.sp,
                    modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            }
        }
        error?.let { message ->
            Surface(Modifier.align(Alignment.Center).padding(18.dp), color = panelColor, shape = RoundedCornerShape(5.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(message, fontSize = 12.sp, lineHeight = 20.sp, color=textColor)
                    TextButton({ retry++ }) { Text("重新加载") }
                }
            }
        }
    }
}
