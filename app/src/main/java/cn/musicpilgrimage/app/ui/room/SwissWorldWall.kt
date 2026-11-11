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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.MapNetworking
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.WallTabFadeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** The operative Opus prototype is also the Android scene; business actions remain native. */
@Composable internal fun SwissWorldWall(
    catalog: Catalog, browse: RoomBrowseState, favorites: Set<String>, favoriteBusy: Boolean,
    onPlace: (String) -> Unit, onFavorite: (String) -> Unit, onTrips: () -> Unit,
    onFootprints: () -> Unit, onAdd: (Place) -> Unit, onSearch: () -> Unit,
    onBack: () -> Unit, visits: Set<String>, active: Boolean,
) {
    val context = LocalContext.current
    val motion = LocalPhysicalMotion.current
    var scene by remember(browse) { mutableStateOf(browse.worldScene) }
    LaunchedEffect(browse, active) {
        if (active && scene == null) {
            // Finish the first tab handoff before creating the heavyweight WebView.
            withFrameNanos { }
            if (motion) delay(WallTabFadeMillis.toLong())
            scene = browse.worldScene ?: WorldScene(context, browse).also { browse.worldScene = it }
        }
    }
    Box(Modifier.fillMaxSize().testTag("world-wall")) {
        scene?.let {
            WorldWebContent(it, catalog, browse, favorites, favoriteBusy, onPlace, onFavorite, onTrips,
                onFootprints, onAdd, onSearch, onBack, visits, active)
        }
        AnimatedVisibility(visible = scene?.hasVisual != true, enter = EnterTransition.None,
            exit = if (motion) fadeOut(tween(130)) else ExitTransition.None) {
            WorldLoadingPreview(browse, onSearch)
        }
    }
}

@Composable private fun WorldLoadingPreview(browse: RoomBrowseState, onSearch: () -> Unit) {
    val context = LocalContext.current
    val longitude = remember(browse) { browse.worldState?.longitude ?: browse.globe.longitude.toDouble() }
    val latitude = remember(browse) { browse.worldState?.latitude ?: browse.globe.latitude.toDouble() }
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
        SwissWallHeader(1, onSearch)
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

internal data class WorldPlaceData(val byId: Map<String, Place>, val json: JSONArray)

@Composable private fun WorldWebContent(
    scene: WorldScene, catalog: Catalog, browse: RoomBrowseState, favorites: Set<String>, favoriteBusy: Boolean,
    onPlace: (String) -> Unit, onFavorite: (String) -> Unit, onTrips: () -> Unit,
    onFootprints: () -> Unit, onAdd: (Place) -> Unit, onSearch: () -> Unit,
    onBack: () -> Unit, visits: Set<String>, active: Boolean,
) {
    val owner = LocalLifecycleOwner.current
    val motion = LocalPhysicalMotion.current
    val pendingFocus = remember(scene) { browse.takeWorldFocus()?.let { it to android.os.SystemClock.elapsedRealtimeNanos().toString() } }
    val placeData by produceState(scene.cachedPlaces(catalog), scene, catalog, active) {
        if (active) value = scene.preparePlaces(catalog)
    }
    val byId = placeData?.byId.orEmpty()
    val json = remember(placeData, favorites, favoriteBusy, visits, pendingFocus, motion) {
        placeData?.let { prepared -> JSONObject().put("places", prepared.json).put("favorites", JSONArray(favorites.toList()))
            .put("visits", JSONArray(visits.toList())).put("favoriteBusy", favoriteBusy)
            .put("state", scene.initialCamera).put("motion", motion)
            .apply { pendingFocus?.let { put("focus", it.first); put("focusToken", it.second) } }.toString()
        }
    }
    val latestAction by rememberUpdatedState<(String, String) -> Unit> { action, id ->
        when (action) {
            "detail" -> if (id in byId) onPlace(id)
            "favorite" -> if (!favoriteBusy && id in byId) onFavorite(id)
            "add" -> byId[id]?.let(onAdd)
            "trips" -> onTrips()
            "footprints" -> onFootprints()
            "search" -> onSearch()
        }
    }
    BackHandler(enabled = active) {
        val source = scene.onAction
        scene.web.evaluateJavascript("window.worldBack ? worldBack() : false") { handled ->
            if (source != null && scene.onAction === source && handled != "true") onBack()
        }
    }
    if (active) DisposableEffect(scene, owner) {
        val handle: (String, String) -> Unit = { action, id -> latestAction(action, id) }
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
                json?.let(scene::update)
            }
        })
}

/** Root-screen lifetime; paused off-screen and destroyed with the root composition. */
@SuppressLint("SetJavaScriptEnabled")
internal class WorldScene(private val context: Context, private val browse: RoomBrowseState) {
    companion object { const val ORIGIN = "https://world.tingzong.local/world/" }
    val media = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Camera updates are already live in this WebView. Only send its seed on boot.
    val initialCamera: JSONObject = browse.worldState?.json() ?: JSONObject()
        .put("longitude", browse.globe.longitude.toDouble()).put("latitude", browse.globe.latitude.toDouble())
    var onAction: ((String, String) -> Unit)? = null
    var hasVisual by mutableStateOf(false)
        private set
    private var ready = false
    private var alive = true
    private var latest = "{}"
    private var rendered: String? = null
    private var focusSent: String? = null
    private var visibilityGeneration = 0
    private var preparedCatalog: Catalog? = null
    private var preparedPlaces: WorldPlaceData? = null
    private val mapResources = WorldResources(context.applicationContext, media)
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
                            "state", "ready" -> Unit
                            else -> onAction?.invoke(action, message.optString("id"))
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
    fun cachedPlaces(catalog: Catalog) = preparedPlaces.takeIf { preparedCatalog === catalog }
    suspend fun preparePlaces(catalog: Catalog): WorldPlaceData {
        cachedPlaces(catalog)?.let { return it }
        val data = withContext(Dispatchers.Default) {
            val places = musicMapCities(catalog, null).flatMap { it.places }.distinctBy { it.id }
            val detail = SwissDetailMedia(context)
            val array = JSONArray()
            places.forEach { place ->
                val item = detail.place(place).put("lat", place.latitude).put("lon", place.longitude)
                val image = item.optString("image")
                if (image.isNotBlank()) {
                    media[place.id] = image
                    item.put("image", ORIGIN + "media/" + Uri.encode(place.id))
                }
                array.put(item)
            }
            WorldPlaceData(places.associateBy { it.id }, array)
        }
        preparedCatalog = catalog
        preparedPlaces = data
        return data
    }
    private fun revealAfterDraw() {
        web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (alive) hasVisual = true }
        })
    }
    fun update(json: String) { latest = json; if (ready) render() }
    private fun render() {
        if (rendered == latest) return
        rendered = latest
        val data = JSONObject(latest)
        if (focusSent == data.optString("focusToken")) data.remove("focus")
        if (data.has("focus")) focusSent = data.optString("focusToken")
        web.evaluateJavascript("window.updateNativeWorld && updateNativeWorld($data)", null)
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
    @Synchronized fun get(context: Context): OkHttpClient = cached ?: MapNetworking.client.newBuilder()
        .cache(Cache(File(context.cacheDir, "world-map-tiles"), 32L * 1024 * 1024)).build().also { cached = it }
}

/** Local trusted origin only. Map traffic uses the existing provider and native identification. */
private class WorldResources(private val context: Context, private val media: Map<String, String>) {
    private val client = WorldMapClient.get(context)
    fun read(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != "world.tingzong.local") return unavailable()
        val path = uri.path?.removePrefix("/world/") ?: return unavailable()
        if (path.contains("..") || path.startsWith('/')) return unavailable()
        return runCatching {
            when {
                path == "map-style.json" -> remote("https://api.maptiler.com/maps/streets-v2/style.json?key=" + Uri.encode(BuildConfig.MAPTILER_KEY.trim()))
                path.startsWith("earth-hd/") || path.startsWith("earth-coast/") -> earthTile(path)
                path.startsWith("provider/") -> remote("https://api.maptiler.com/" + path.removePrefix("provider/") + (uri.encodedQuery?.let { "?$it" } ?: ""))
                path.startsWith("media/") -> image(media[path.removePrefix("media/")] ?: return unavailable())
                path == "font.woff2" -> asset("design/swiss-details/fonts/swiss-sans.woff2", "font/woff2")
                path == "inter-variable.woff2" -> asset("design/swiss-details/fonts/inter-variable.woff2", "font/woff2")
                else -> asset("design/swiss-world/$path", mime(path))
            }
        }.getOrElse { unavailable() }
    }
    private fun image(source: String): WebResourceResponse = when {
        source.startsWith("media/") && !source.contains("..") -> asset("design/swiss-details/$source", mime(source))
        source.startsWith("file:///android_asset/") && !source.contains("..") -> asset(source.removePrefix("file:///android_asset/"), mime(source))
        source.startsWith("https://") -> remote(source)
        source.startsWith("file://") -> {
            val file = File(Uri.parse(source).path.orEmpty()).canonicalFile
            if (!file.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && !file.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) unavailable()
            else response(mime(source), file.inputStream())
        }
        else -> unavailable()
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
        return remote("https://api.maptiler.com/tiles/$endpoint?key=" + Uri.encode(BuildConfig.MAPTILER_KEY.trim()), preferCached = true)
    }
    private fun remote(url: String, preferCached: Boolean = false): WebResourceResponse {
        val request = Request.Builder().url(url).header("User-Agent", "cn.musicpilgrimage.app/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; MapLibre Android)").build()
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
