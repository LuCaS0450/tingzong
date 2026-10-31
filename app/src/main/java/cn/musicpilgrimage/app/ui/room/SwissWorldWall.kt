package cn.musicpilgrimage.app.ui.room

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.MapNetworking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream

/** The operative Opus prototype is also the Android scene; business actions remain native. */
@Composable internal fun SwissWorldWall(
    catalog: Catalog, browse: RoomBrowseState, favorites: Set<String>, favoriteBusy: Boolean,
    onPlace: (String) -> Unit, onFavorite: (String) -> Unit, onTrips: () -> Unit,
    onFootprints: () -> Unit, onAdd: (Place) -> Unit, onSearch: () -> Unit,
    onBack: () -> Unit, visits: Set<String>,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val places = remember(catalog) { musicMapCities(catalog, null).flatMap { it.places }.distinctBy { it.id } }
    val byId = remember(places) { places.associateBy { it.id } }
    val media = remember(context) { SwissDetailMedia(context) }
    val scene = remember(browse) { browse.worldScene ?: WorldScene(context, browse).also { browse.worldScene = it } }
    val pendingFocus = remember(scene) { browse.takeWorldFocus()?.let { it to android.os.SystemClock.elapsedRealtimeNanos().toString() } }
    val placeData = remember(places) {
        JSONArray().also { array -> places.forEach { place ->
            val item = media.place(place).put("lat", place.latitude).put("lon", place.longitude)
            val image = item.optString("image")
            if (image.isNotBlank()) {
                scene.media[place.id] = image
                item.put("image", WorldScene.ORIGIN + "media/" + Uri.encode(place.id))
            }
            array.put(item)
        } }
    }
    val json = remember(placeData, favorites, favoriteBusy, visits) {
        JSONObject().put("places", placeData).put("favorites", JSONArray(favorites.toList()))
            .put("visits", JSONArray(visits.toList())).put("favoriteBusy", favoriteBusy)
            .put("state", browse.worldState?.json() ?: JSONObject()
                .put("longitude", browse.globe.longitude.toDouble()).put("latitude", browse.globe.latitude.toDouble()))
            .apply { pendingFocus?.let { put("focus", it.first); put("focusToken", it.second) } }.toString()
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
    BackHandler { scene.web.evaluateJavascript("window.worldBack ? worldBack() : false") { handled -> if (handled != "true") onBack() } }
    DisposableEffect(scene, owner) {
        scene.onAction = { action, id -> latestAction(action, id) }
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
            scene.onAction = null
            scene.pause()
        }
    }
    AndroidView(factory = { scene.web }, modifier = Modifier.fillMaxSize().testTag("world-wall"),
        onRelease = { /* Keep the GPU scene for an immediate, stationary details return. */ },
        update = { scene.update(json) })
}

/** Root-screen lifetime; paused off-screen and destroyed with the root composition. */
@SuppressLint("SetJavaScriptEnabled")
internal class WorldScene(context: Context, private val browse: RoomBrowseState) {
    companion object { const val ORIGIN = "https://world.tingzong.local/world/" }
    val media = java.util.concurrent.ConcurrentHashMap<String, String>()
    var onAction: ((String, String) -> Unit)? = null
    private var ready = false
    private var alive = true
    private var latest = "{}"
    private var rendered: String? = null
    private var focusSent: String? = null
    private var visibilityGeneration = 0
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
                        if (action !in setOf("state", "ready")) onAction?.invoke(action, message.optString("id"))
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
                path.startsWith("provider/") -> remote("https://api.maptiler.com/" + path.removePrefix("provider/") + (uri.encodedQuery?.let { "?$it" } ?: ""))
                path.startsWith("media/") -> image(media[path.removePrefix("media/")] ?: return unavailable())
                path == "font.woff2" -> asset("design/swiss-details/fonts/swiss-sans.woff2", "font/woff2")
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
    private fun remote(url: String): WebResourceResponse {
        val request = Request.Builder().url(url).header("User-Agent", "cn.musicpilgrimage.app/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; MapLibre Android)").build()
        val result = client.newCall(request).execute()
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
