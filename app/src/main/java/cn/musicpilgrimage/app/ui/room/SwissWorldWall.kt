package cn.musicpilgrimage.app.ui.room

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.PilgrimageApplication
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.AppArt
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.WallTabFadeMillis
import cn.musicpilgrimage.app.ui.LocalRouteIsCurrent
import cn.musicpilgrimage.app.ui.appMotionEasing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import kotlin.math.*

private suspend fun awaitWorldWebView(context: Context) =
    (context.applicationContext as PilgrimageApplication).awaitWebViewStartup()

private const val InitialWorldLongitude = 35.0
private const val InitialWorldLatitude = 24.0

private fun Color.cssHex() = "#" + (toArgb() and 0xffffff).toString(16).padStart(6, '0')

private val worldPalette = JSONObject(mapOf(
    "paper" to AppArt.Paper.cssHex(),
    "ink" to AppArt.Ink.cssHex(),
    "muted" to AppArt.WallMuted.cssHex(),
    "line" to AppArt.Line.cssHex(),
    "red" to AppArt.Red.cssHex(),
))

/** MapLibre owns the map surface. Place browsing and actions are available before it starts. */
@Composable internal fun SwissWorldWall(
    catalog: Catalog, browse: RoomBrowseState, favorites: Set<String>, favoriteBusy: Boolean,
    onPlace: (String) -> Unit, onFavorite: (String) -> Unit, onTrips: () -> Unit,
    onFootprints: () -> Unit, onAdd: (Place) -> Unit, onSearch: () -> Unit,
    onBack: () -> Unit, visits: Set<String>, active: Boolean,
    awaitMapStartup: suspend (Context) -> Unit = ::awaitWorldWebView,
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
    var viewportWidth by remember { mutableIntStateOf(0) }
    val summaryHeight = measurement?.takeIf { it.first == summary?.place?.id }?.second
    val heightRatio = summaryHeight?.takeIf { viewportWidth > 0 }?.let { it.toFloat() / viewportWidth } ?: 0f
    val focus = browse.worldFocus?.takeIf { it.placeId in byId && (summary == null || heightRatio > 0f) }
    var scene by remember(browse) { mutableStateOf(browse.worldScene) }
    fun showChooser(ids: List<String>) {
        chooserSequence++
        chooser = ids
    }
    BackHandler(enabled = active) {
        when {
            chooser != null -> chooser = null
            summary != null -> browse.closeWorldSummary()
            else -> {
                val map = scene
                if (map?.hasVisual == true) map.back { handled ->
                    if (isCurrent() && !handled) onBack()
                } else onBack()
            }
        }
    }
    LaunchedEffect(active) { if (!active) chooser = null }
    LaunchedEffect(browse, active) {
        if (active && scene == null) {
            // Give the native preview a frame before creating the WebView.
            withFrameNanos { }
            awaitMapStartup(context)
            scene = browse.worldScene ?: WorldScene(context, browse).also { browse.worldScene = it }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewportWidth = it.width }.testTag("world-wall")) {
        val unit = maxWidth / 360
        scene?.let {
            WorldWebContent(it, entries, selection, focus, heightRatio, visits, active) { message ->
                if (isCurrent()) when (message.optString("action")) {
                    "select-place" -> message.optString("id").takeIf { it in byId }?.let { id -> browse.focusWorld(id, WorldFocusKind.Pin) }
                    "close-place" -> if (browse.worldSelection?.placeId == message.optString("id")) browse.closeWorldSummary()
                    "choose-places" -> message.optJSONArray("ids")?.let { ids ->
                        showChooser((0 until ids.length()).map(ids::optString).filter { id -> id in byId })
                    }
                    "trips" -> onTrips()
                    "footprints" -> onFootprints()
                }
            }
        }
        AnimatedVisibility(visible = scene?.hasVisual != true, enter = EnterTransition.None,
            exit = if (motion) fadeOut(tween(WallTabFadeMillis,easing=appMotionEasing)) else ExitTransition.None) {
            WorldLoadingPreview(browse, "${cities.size} 座城市 / ${entries.size} 个地点")
        }
        val compact = scene?.compactHeader == true
        SwissTextButton(
            { showChooser(emptyList()) },
            Modifier.align(Alignment.TopEnd).offset(y = unit * if (compact) 5 else 83)
                .padding(end = unit * if (compact) 57 else 22).heightIn(min = 48.dp).testTag("world-place-list"),
            enabled = active, contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null, Modifier.size(18.dp), tint = RoomColors.Ink)
            Spacer(Modifier.width(5.dp))
            Text("地点", color = RoomColors.Ink, fontWeight = FontWeight.Medium)
        }
        IconButton(onSearch, Modifier.align(Alignment.TopEnd).offset(y = unit * if (compact) 5 else 10)
            .padding(end = unit * 10).testTag("world-search"), enabled = active) {
            Icon(Icons.Outlined.Search, "搜索歌曲与地点", tint = RoomColors.Ink)
        }
        WorldPlaceSummary(
            summary, Modifier.align(Alignment.BottomCenter), favorites, favoriteBusy, interactive = active,
            onDismiss = browse::closeWorldSummary, onReopen = browse::reopenWorldSummary,
            onMeasure = { id, height -> measurement = id to height },
            onFavorite = onFavorite, onAround = { browse.focusWorld(it, WorldFocusKind.Street) },
            onAdd = onAdd, onDetail = onPlace,
        )
        chooser?.takeIf { active }?.let { ids ->
            val options = remember(entries, ids) { if (ids.isEmpty()) entries else ids.mapNotNull(byId::get) }
            key(chooserSequence) {
                WorldPlaceChooser(if (ids.isEmpty()) "音乐地点" else "此处 ${options.size} 个地点", options, { chooser = null }) { id ->
                    chooser = null
                    browse.focusWorld(id, WorldFocusKind.List)
                }
            }
        }
    }
}

@Composable private fun WorldLoadingPreview(browse: RoomBrowseState, countLabel: String) {
    val context = LocalContext.current
    val longitude = remember(browse) { browse.worldState?.longitude ?: InitialWorldLongitude }
    val latitude = remember(browse) { browse.worldState?.latitude ?: InitialWorldLatitude }
    val preview by produceState<ImageBitmap?>(null, longitude, latitude) {
        value = withContext(Dispatchers.Default) { drawStartupGlobe(context, longitude, latitude) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(RoomColors.Wall).testTag("world-loading-preview")) {
        RoomShelfWall(Modifier.matchParentSize())
        val diameter = maxWidth * (304f / 360)
        val center = minOf(maxWidth * (352f / 360), (maxHeight + maxWidth * (64f / 360)) / 2)
        preview?.let {
            Image(it, contentDescription = null,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = center - diameter / 2).size(diameter))
        }
        val unit = maxWidth / 360
        Column(Modifier.offset(x = unit * 22, y = unit * 22), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SwissEyebrow("02 / WORLD")
            Text("地图与旅行", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = RoomColors.Ink)
            Text(countLabel, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = RoomColors.Muted)
        }
        Text(
            "正在打开世界…",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            color = RoomColors.Muted,
            fontSize = 13.sp,
        )
    }
}

/** The orthographic preview uses the same bundled texture and camera as the WebView preview. */
private fun drawStartupGlobe(context: Context, longitude: Double, latitude: Double): ImageBitmap? {
    val texture = context.assets.open("design/swiss-world/earth-1024.jpg").use { BitmapFactory.decodeStream(it) } ?: return null
    val width = texture.width
    val height = texture.height
    val pixels = IntArray(width * height)
    texture.getPixels(pixels, 0, width, 0, 0, width, height)
    texture.recycle()
    val size = 224
    val globe = IntArray(size * size)
    val lon = Math.toRadians(longitude)
    val tilt = Math.toRadians(latitude.coerceIn(-80.0, 80.0))
    val sinTilt = sin(tilt)
    val cosTilt = cos(tilt)
    for (row in 0 until size) for (column in 0 until size) {
        val x = (column + .5) * 2 / size - 1
        val y = 1 - (row + .5) * 2 / size
        val distance = x * x + y * y
        if (distance >= 1) continue
        val z = sqrt(1 - distance)
        val u = ((lon + atan2(x, z * cosTilt - y * sinTilt)) / (2 * PI) + 1.5) % 1
        val v = .5 - asin((y * cosTilt + z * sinTilt).coerceIn(-1.0, 1.0)) / PI
        val color = pixels[(v * height).toInt().coerceIn(0, height - 1) * width + (u * width).toInt().coerceIn(0, width - 1)]
        val alpha = ((1 - sqrt(distance)) * size / 2 * 255).toInt().coerceAtMost(255)
        // Match the WebView preview's sphere lighting before it takes over.
        val facing = -.49 * x + .55 * y + .68 * z
        val t = ((facing + .26) / .98).coerceIn(0.0, 1.0)
        val shadow = .24 * (1 - t * t * (3 - 2 * t))
        val specular = ((facing - .45) / .55).coerceAtLeast(0.0)
        val highlight = .115 * specular * specular
        val light = (1 - shadow) * (1 - highlight)
        val red = ((color ushr 16 and 255) * light + 255 * highlight).roundToInt()
        val green = ((color ushr 8 and 255) * light + 255 * highlight).roundToInt()
        val blue = ((color and 255) * light + 255 * highlight).roundToInt()
        globe[row * size + column] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return Bitmap.createBitmap(globe, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable private fun WorldWebContent(
    scene: WorldScene, entries: List<WorldPlaceEntry>, selection: WorldSelection?, focus: WorldFocus?,
    heightRatio: Float, visits: Set<String>, active: Boolean, onMapAction: (JSONObject) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val motion = LocalPhysicalMotion.current
    val markers by produceState(scene.cachedMarkers(entries), scene, entries, active) {
        if (active) value = scene.prepareMarkers(entries)
    }
    val latestAction by rememberUpdatedState(onMapAction)
    if (active) DisposableEffect(scene, owner) {
        val handle: (JSONObject) -> Unit = { message -> latestAction(message) }
        scene.onAction = handle
        scene.resume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scene.resume()
                Lifecycle.Event.ON_PAUSE -> scene.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (scene.onAction === handle) {
                scene.onAction = null
                scene.pause()
            }
        }
    }
    AndroidView(factory = { FrameLayout(it) }, modifier = Modifier.fillMaxSize(),
        onRelease = { it.removeAllViews() }, update = { host ->
            if (active) {
                if (scene.web.parent !== host) {
                    (scene.web.parent as? ViewGroup)?.removeView(scene.web)
                    host.addView(scene.web)
                }
                markers?.let { scene.update(it, selection, focus, heightRatio, visits, motion) }
            }
        })
}

/** Root-screen lifetime; paused off-screen and destroyed with the root composition. */
@SuppressLint("SetJavaScriptEnabled")
internal class WorldScene(private val context: Context, private val browse: RoomBrowseState) {
    companion object { const val ORIGIN = "https://world.tingzong.local/world/" }
    // Camera updates are already live in this WebView. Only send its seed on boot.
    val initialCamera: JSONObject = browse.worldState?.json() ?: JSONObject()
        .put("longitude", InitialWorldLongitude).put("latitude", InitialWorldLatitude)
    var onAction: ((JSONObject) -> Unit)? = null
    var hasVisual by mutableStateOf(false)
        private set
    var compactHeader by mutableStateOf((browse.worldState?.zoom ?: 0.0) >= 3)
        private set
    private var ready = false
    private var alive = true
    private var latest = "{}"
    private var rendered: String? = null
    private var latestMarkers: JSONArray? = null
    private var engineBooted = false
    private var visibilityGeneration = 0
    private var preparedEntries: List<WorldPlaceEntry>? = null
    private var preparedMarkers: JSONArray? = null
    private var sentMarkers: JSONArray? = null
    private val mapResources = WorldResources(context.applicationContext)
    val web = WebView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        // The CSS world covers the whole view; an opaque surface avoids an extra alpha composition.
        setBackgroundColor(android.graphics.Color.rgb(238, 238, 231))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.textZoom = 100
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(raw: String) {
                if (raw.length > 16_384) return
                val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
                val snapshot = WorldCameraSnapshot.parse(message.optJSONObject("state")) ?: return
                post {
                    if (alive) {
                        browse.world(snapshot)
                        val action = message.optString("action")
                        when (action) {
                            "visual-ready" -> revealAfterDraw()
                            "header-layout" -> compactHeader = message.optBoolean("compact")
                            "ready" -> engineBooted = true
                            "state" -> Unit
                            else -> onAction?.invoke(message)
                        }
                    }
                }
            }
        }, "TingzongWorld")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Never print raw provider exceptions: their URLs can contain the existing key.
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    android.util.Log.e("TingzongWorld", "World script error at line ${message.lineNumber()}")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = mapResources.read(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.toString() != ORIGIN + "index.html"
            override fun onPageFinished(view: WebView, url: String) {
                if (url == ORIGIN + "index.html") { ready = true; render() }
            }
        }
        loadUrl(ORIGIN + "index.html")
    }
    fun cachedMarkers(entries: List<WorldPlaceEntry>) = preparedMarkers.takeIf { preparedEntries === entries }
    suspend fun prepareMarkers(entries: List<WorldPlaceEntry>): JSONArray {
        cachedMarkers(entries)?.let { return it }
        val data = withContext(Dispatchers.Default) {
            JSONArray(entries.map { entry ->
                val place = entry.place
                val country = canonicalRegionName(place.country)
                JSONObject().put("id", place.id).put("name", entry.name)
                    .put("lat", place.latitude).put("lon", place.longitude)
                    .put("country", country).put("city", place.city)
                    .put("cityGroup", unifiedDestinationRegion(country) ?: place.city)
            })
        }
        preparedEntries = entries
        preparedMarkers = data
        return data
    }
    private fun revealAfterDraw() {
        web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (alive) hasVisual = true }
        })
    }
    fun update(markers: JSONArray, selection: WorldSelection?, focus: WorldFocus?, heightRatio: Float, visits: Set<String>, motion: Boolean) {
        latestMarkers = markers
        latest = JSONObject().put("nativePlacePanels", true).put("motion", motion)
            .put("selectionId", selection?.placeId.orEmpty()).put("summaryOpen", selection?.summaryOpen == true)
            .put("summaryHeightRatio", heightRatio).put("visits", JSONArray(visits.toList()))
            .put("state", initialCamera)
            .apply {
                focus?.let { request -> put("mapFocus", JSONObject().put("id", request.placeId)
                    .put("kind", request.kind.name).put("token", request.token)) }
            }.toString()
        if (ready) render()
    }
    private fun render() {
        val markers = latestMarkers ?: return
        if (rendered == latest && sentMarkers === markers) return
        rendered = latest
        val data = JSONObject(latest)
        if (!engineBooted) data.put("palette", worldPalette).put("providerHost", WorldTileProvider.host)
        // Keep full marker data in the boot payload. Later panel updates do not rebuild sources.
        if (!engineBooted || sentMarkers !== markers) data.put("places", markers)
        sentMarkers = markers
        web.evaluateJavascript("window.updateNativeWorld && updateNativeWorld($data)", null)
    }
    fun back(onResult: (Boolean) -> Unit) {
        val source = onAction
        web.evaluateJavascript("window.worldBack ? worldBack() : false") { value ->
            if (alive && source != null && source === onAction) onResult(value == "true")
        }
    }
    fun resume() { visibilityGeneration++; web.onResume() }
    fun pause() {
        if (!alive) return
        val generation = ++visibilityGeneration
        if (ready) web.evaluateJavascript("window.pauseWorld && pauseWorld()") { if (alive && generation == visibilityGeneration) web.onPause() }
        else web.onPause()
    }
    fun destroy() {
        alive = false
        onAction = null
        web.removeJavascriptInterface("TingzongWorld")
        web.stopLoading()
        web.destroy()
    }
}

private object WorldMapClient {
    @Volatile private var cached: OkHttpClient? = null
    @Synchronized fun get(context: Context): OkHttpClient = cached ?: OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "world-map-tiles"), 32L * 1024 * 1024)).build().also { cached = it }
}

private object WorldTileProvider {
    const val host = "api.maptiler.com"
    private const val root = "https://$host/"
    private val encodedKey get() = Uri.encode(BuildConfig.MAPTILER_KEY.trim())
    private val userAgent = "cn.musicpilgrimage.app/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; MapLibre Android)"

    fun streetStyle() = "${root}maps/streets-v2/style.json?key=$encodedKey"
    fun tile(path: String) = "${root}tiles/$path?key=$encodedKey"
    fun resource(path: String, query: String?) = root + path + query?.let { "?$it" }.orEmpty()
    fun request(url: String) = Request.Builder().url(url).header("User-Agent", userAgent).build()
}

/** Local trusted origin only. Map traffic uses the existing provider and native identification. */
private class WorldResources(private val context: Context) {
    private val client = WorldMapClient.get(context)
    fun read(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != "world.tingzong.local") return unavailable()
        val path = uri.path?.removePrefix("/world/") ?: return unavailable()
        if (path.contains("..") || path.startsWith('/')) return unavailable()
        return runCatching {
            when {
                path == "map-style.json" -> remote(WorldTileProvider.streetStyle())
                path.startsWith("earth-hd/") || path.startsWith("earth-coast/") -> earthTile(path)
                path.startsWith("provider/") -> remote(WorldTileProvider.resource(path.removePrefix("provider/"), uri.encodedQuery))
                path == "font.woff2" -> asset("design/swiss-details/fonts/swiss-sans.woff2", "font/woff2")
                path == "inter-variable.woff2" -> asset("design/swiss-details/fonts/inter-variable.woff2", "font/woff2")
                else -> asset("design/swiss-world/$path", mime(path))
            }
        }.getOrElse { unavailable() }
    }
    private fun asset(path: String, type: String) = response(type, context.assets.open(path))
    private fun earthTile(path: String): WebResourceResponse {
        val parts = Regex("(earth-hd|earth-coast)/(\\d+)/(\\d+)/(\\d+)\\.(jpg|pbf)")
            .matchEntire(path)?.groupValues ?: return unavailable()
        val z = parts[2].toIntOrNull() ?: return unavailable()
        val x = parts[3].toIntOrNull() ?: return unavailable()
        val y = parts[4].toIntOrNull() ?: return unavailable()
        if (z !in 3..9 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return unavailable()
        val endpoint = when {
            parts[1] == "earth-hd" && parts[5] == "jpg" -> "satellite-v2/$z/$x/$y.jpg"
            parts[1] == "earth-coast" && parts[5] == "pbf" && z >= 5 -> "v3/$z/$x/$y"
            else -> return unavailable()
        }
        return remote(WorldTileProvider.tile(endpoint), preferCached = true)
    }
    private fun remote(url: String, preferCached: Boolean = false): WebResourceResponse {
        val request = WorldTileProvider.request(url)
        // Satellite imagery is not live data. Retain the existing bounded cache for offline revisits.
        val cached = if (preferCached) client.newCall(request.newBuilder().cacheControl(okhttp3.CacheControl.FORCE_CACHE).build()).execute() else null
        val result = if (cached?.isSuccessful == true) cached else { cached?.close(); client.newCall(request).execute() }
        val body = result.body
        if (!result.isSuccessful || body == null) { result.close(); return unavailable() }
        val stream = object : FilterInputStream(body.byteStream()) { override fun close() { try { super.close() } finally { result.close() } } }
        return response(body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream", stream)
    }
    private fun response(type: String, stream: java.io.InputStream) = WebResourceResponse(type, "UTF-8", 200, "OK", mapOf("Cache-Control" to "public, max-age=3600", "X-Content-Type-Options" to "nosniff"), stream)
    private fun unavailable() = WebResourceResponse("text/plain", "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun mime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"
        "json" -> "application/json"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
        "webp" -> "image/webp"; "woff2" -> "font/woff2"; else -> "text/plain"
    }
}
