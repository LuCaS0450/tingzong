package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import cn.musicpilgrimage.app.BuildConfig
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.common.HttpRequest
import com.mapbox.common.HttpRequestError
import com.mapbox.common.HttpRequestErrorType
import com.mapbox.common.HttpRequestOrResponse
import com.mapbox.common.HttpResponse
import com.mapbox.common.HttpResponseData
import com.mapbox.common.HttpServiceFactory
import com.mapbox.common.HttpServiceInterceptorInterface
import com.mapbox.common.HttpServiceInterceptorRequestContinuation
import com.mapbox.common.HttpServiceInterceptorResponseContinuation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mapbox style with bundled satellite imagery and a cache for graded online imagery.
 */
internal object WorldTilePipeline {
    private const val MAPTILER_HOST = "api.maptiler.com"
    private const val GRADED_EARTH_HOST = "earth-grade.tingzong.local"
    private val USER_AGENT =
        "cn.musicpilgrimage.app/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

    private val TILE_PATH_REGEX = Regex("^/v2/(\\d+)/(\\d+)/(\\d+)\\.jpg$")
    private val POW_LUT = DoubleArray(256) { i -> (i / 255.0).pow(0.6) }

    private val DEEP_OPTICAL = intArrayOf(118, 172, 194)
    private val SHALLOW_OPTICAL = intArrayOf(178, 214, 214)
    private val NATURAL_DEEP = intArrayOf(29, 80, 111)
    private val NATURAL_SHALLOW = intArrayOf(68, 151, 145)
    private val PAPER_RGB = intArrayOf(247, 246, 242)
    private val LAND_TINT = doubleArrayOf(1.03, 1.0, 0.93)

    @Volatile
    private var cachedClient: OkHttpClient? = null

    @Volatile
    private var cachedOceanAtlas: OceanAtlas? = null

    // A zoom gesture can invalidate the view before auxiliary coast requests finish.
    private val coastExecutor = ThreadPoolExecutor(
        6, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
    ).apply { allowCoreThreadTimeOut(true) }
    private val cacheMaintenance = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastHdFailureAtMs: Long = 0L

    @Volatile
    private var httpClientConfigured: Boolean = false

    private class OceanAtlas(val width: Int, val height: Int, val pixels: IntArray)

    @Synchronized
    fun configureMapboxTileTransport(context: Context) {
        if (httpClientConfigured) return
        val client = getClient(context.applicationContext)
        HttpServiceFactory.setHttpServiceInterceptor(object : HttpServiceInterceptorInterface {
            override fun onRequest(request: HttpRequest, continuation: HttpServiceInterceptorRequestContinuation) {
                val url = request.url.toHttpUrlOrNull()
                if (url?.host != GRADED_EARTH_HOST) {
                    continuation.run(HttpRequestOrResponse(request))
                    return
                }
                client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.run(HttpRequestOrResponse(satelliteTransportError(request)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val nativeResponse = try {
                            response.use { tile ->
                                val headers = HashMap<String, String>()
                                for ((name, value) in tile.headers) headers[name.lowercase()] = value
                                headers.remove("content-encoding")
                                headers.remove("content-length")
                                val data = HttpResponseData(headers, tile.code, tile.body?.bytes() ?: ByteArray(0))
                                HttpResponse(request.id ?: 0, request, ExpectedFactory.createValue(data))
                            }
                        } catch (_: IOException) {
                            satelliteTransportError(request)
                        }
                        continuation.run(HttpRequestOrResponse(nativeResponse))
                    }
                })
            }

            override fun onResponse(response: HttpResponse, continuation: HttpServiceInterceptorResponseContinuation) {
                continuation.run(response)
            }
        })
        httpClientConfigured = true
    }

    private fun satelliteTransportError(request: HttpRequest) = HttpResponse(
        request.id ?: 0,
        request,
        ExpectedFactory.createError(HttpRequestError(HttpRequestErrorType.CONNECTION_ERROR, "Satellite tile unavailable")),
    )

    fun recentHdErrorSince(sinceMs: Long): Boolean = lastHdFailureAtMs > sinceMs

    fun resetErrorFlags() {
        lastHdFailureAtMs = 0L
    }

    private fun getClient(context: Context): OkHttpClient {
        cachedClient?.let { return it }
        val appContext = context.applicationContext
        val cacheDir = File(appContext.cacheDir, "world-map-tiles")
        val legacyGradedDir = File(cacheDir, "graded-earth")
        if (legacyGradedDir.exists()) {
            cacheMaintenance.execute { runCatching { legacyGradedDir.deleteRecursively() } }
        }
        val gradedCacheDir = File(cacheDir, "graded-earth-v2")
        val coastClient = OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "coast-v3"), 16L * 1024 * 1024))
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
        val client = coastClient.newBuilder()
            .cache(Cache(cacheDir, 48L * 1024 * 1024))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addInterceptor(GradedEarthInterceptor(appContext, coastClient, gradedCacheDir))
            .addInterceptor(SatelliteCacheFallback())
            .build()
        cachedClient = client
        return client
    }

    private fun loadOceanAtlas(context: Context): OceanAtlas? {
        cachedOceanAtlas?.let { return it }
        synchronized(this) {
            cachedOceanAtlas?.let { return it }
            val loaded = runCatching {
                context.assets.open("design/swiss-world/ocean-colors.png").use { stream ->
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inPremultiplied = false
                    }
                    val bmp = BitmapFactory.decodeStream(stream, null, opts) ?: return@runCatching null
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val atlas = OceanAtlas(bmp.width, bmp.height, pixels)
                    bmp.recycle()
                    atlas
                }
            }.getOrNull()
            if (loaded != null) cachedOceanAtlas = loaded
            return loaded
        }
    }

    private class SatelliteCacheFallback : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val req = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            return try {
                chain.proceed(req)
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                // Reuse a cached satellite tile after a connection failure.
                val cachedReq = req.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
                val cachedResp = runCatching { chain.proceed(cachedReq) }.getOrNull()
                if (cachedResp != null && cachedResp.isSuccessful) {
                    cachedResp
                } else {
                    cachedResp?.close()
                    syntheticError(req, 504, "Offline")
                }
            }
        }
    }

    private class GradedEarthInterceptor(
        private val appContext: Context,
        private val coastClient: OkHttpClient,
        private val gradedCacheDir: File,
    ) : Interceptor {
        private var writesSincePrune = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (req.url.host != GRADED_EARTH_HOST) {
                return chain.proceed(req)
            }
            val match = TILE_PATH_REGEX.matchEntire(req.url.encodedPath)
                ?: return syntheticError(req, 404, "Not Found")
            val z = match.groupValues[1].toIntOrNull() ?: return syntheticError(req, 400, "Bad Zoom")
            val x = match.groupValues[2].toIntOrNull() ?: return syntheticError(req, 400, "Bad X")
            val y = match.groupValues[3].toIntOrNull() ?: return syntheticError(req, 400, "Bad Y")
            val maxIndex = 1 shl z
            if (z !in 3..9 || x !in 0 until maxIndex || y !in 0 until maxIndex) {
                return syntheticError(req, 404, "Out of range")
            }
            val tileFile = File(gradedCacheDir, "${z}_${x}_${y}.jpg")
            if (tileFile.isFile && tileFile.length() > 0L) {
                val cachedBytes = runCatching { tileFile.readBytes() }.getOrNull()
                if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                    lastHdFailureAtMs = 0L
                    return okTileResponse(req, cachedBytes)
                }
            }
            val key = BuildConfig.MAPTILER_KEY.trim()
            if (key.isEmpty()) {
                return syntheticError(req, 503, "No tile key")
            }
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val satelliteUrl = "https://$MAPTILER_HOST/tiles/satellite-v2/$z/$x/$y.jpg?key=$encodedKey"
            val upstreamReq = Request.Builder()
                .url(satelliteUrl)
                .build()
            val coastCall = if (z >= 5) {
                coastClient.newCall(Request.Builder()
                    .url("https://$MAPTILER_HOST/tiles/v3/$z/$x/$y?key=$encodedKey")
                    .header("User-Agent", USER_AGENT)
                    .build())
            } else null
            val coastTask = coastCall?.let { call ->
                FutureTask<ByteArray?> {
                    call.execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
                }.also { task ->
                    try {
                        coastExecutor.execute(task)
                    } catch (_: RejectedExecutionException) {
                        task.cancel(false)
                    }
                }
            }
            val coastDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2600L)
            return try {
                chain.proceed(upstreamReq).use { upstreamResp ->
                    if (!upstreamResp.isSuccessful) {
                        lastHdFailureAtMs = System.currentTimeMillis()
                        return syntheticError(req, upstreamResp.code, "Tile upstream unavailable", upstreamResp.header("Retry-After"))
                    }
                    val rawBytes = upstreamResp.body?.bytes()
                    if (rawBytes == null || rawBytes.isEmpty()) {
                        lastHdFailureAtMs = System.currentTimeMillis()
                        return syntheticError(req, 503, "Empty tile")
                    }
                    val coastBytes = if (coastTask != null && !chain.call().isCanceled()) {
                        val remainingNs = (coastDeadlineNs - System.nanoTime()).coerceAtLeast(0)
                        runCatching { coastTask.get(remainingNs, TimeUnit.NANOSECONDS) }.getOrNull()
                    } else null
                    if (chain.call().isCanceled()) throw IOException("Tile cancelled")
                    val graded = gradeSatelliteBytes(appContext, rawBytes, z, x, y, coastBytes)
                    if (graded == null) {
                        lastHdFailureAtMs = System.currentTimeMillis()
                        return syntheticError(req, 503, "Unreadable tile")
                    }
                    val complete = z < 5 || coastBytes != null
                    if (complete && !chain.call().isCanceled()) persistGradedTile(tileFile, graded)
                    lastHdFailureAtMs = 0L
                    // Keep the heuristic fallback usable while the coast data recovers.
                    okTileResponse(req, graded, maxAgeSeconds = if (complete) 2592000 else 60)
                }
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                lastHdFailureAtMs = System.currentTimeMillis()
                syntheticError(req, 504, "Offline")
            } finally {
                if (coastTask?.isDone == false) {
                    coastCall?.cancel()
                    coastTask.cancel(false)
                    coastExecutor.remove(coastTask)
                }
            }
        }

        private fun okTileResponse(req: Request, bytes: ByteArray, maxAgeSeconds: Int = 2592000): Response =
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "public, max-age=$maxAgeSeconds")
                .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                .build()

        private fun persistGradedTile(tileFile: File, bytes: ByteArray) {
            runCatching {
                gradedCacheDir.mkdirs()
                val tmp = File.createTempFile(".tmp_", null, gradedCacheDir)
                try {
                    tmp.writeBytes(bytes)
                    tmp.renameTo(tileFile)
                } finally {
                    tmp.delete()
                }
                synchronized(this) {
                    if (++writesSincePrune < 32) return
                    writesSincePrune = 0
                }
                cacheMaintenance.execute {
                    val cached = gradedCacheDir.listFiles { f -> f.isFile && f.extension == "jpg" }
                    if (cached != null && cached.size > 400) {
                        cached.sortBy { it.lastModified() }
                        for (i in 0 until cached.size - 320) cached[i].delete()
                    }
                }
            }
        }
    }

    private fun syntheticError(req: Request, code: Int, message: String, retryAfter: String? = null): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .header("Cache-Control", "no-store")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
            .build()

    private fun gradeSatelliteBytes(
        context: Context,
        rawBytes: ByteArray,
        z: Int,
        tileX: Int,
        tileY: Int,
        coastPbfBytes: ByteArray? = null,
    ): ByteArray? {
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val atlas = loadOceanAtlas(context)
            val oceanMask = if (coastPbfBytes != null && coastPbfBytes.isNotEmpty()) {
                runCatching { decodeOceanMask(coastPbfBytes, w, h) }.getOrNull()
            } else null
            gradePixels(pixels, w, h, z, tileX, tileY, atlas, oceanMask)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            val out = ByteArrayOutputStream(rawBytes.size)
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    internal class OceanMaskGeometry(
        val extent: Int,
        val polygons: List<List<FloatArray>>,
    )

    private class MvtReader(private val bytes: ByteArray) {
        var pos: Int = 0
        val done: Boolean get() = pos >= bytes.size

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (!done && shift < 64) {
                val b = bytes[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("Truncated varint")
        }

        fun take(length: Int): ByteArray {
            require(length >= 0 && pos + length <= bytes.size) { "Truncated field" }
            val slice = bytes.copyOfRange(pos, pos + length)
            pos += length
            return slice
        }

        inline fun forEachField(block: (field: Int, wireType: Int, longVal: Long, bytesVal: ByteArray?) -> Unit) {
            while (!done) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7L).toInt()
                if (field <= 0) throw IllegalArgumentException("Invalid field")
                when (wire) {
                    0 -> block(field, wire, varint(), null)
                    2 -> {
                        val len = varint().toInt()
                        block(field, wire, 0L, take(len))
                    }
                    1 -> { take(8) }
                    5 -> { take(4) }
                    else -> throw IllegalArgumentException("Unsupported wire type: $wire")
                }
            }
        }
    }

    private fun gunzipIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { return it.readBytes() }
        }
        return raw
    }

    internal fun extractOceanPolygons(pbfBytes: ByteArray): OceanMaskGeometry? {
        val data = gunzipIfNeeded(pbfBytes)
        var foundWater = false
        var extent = 4096
        val polygons = ArrayList<List<FloatArray>>()
        MvtReader(data).forEachField { field, _, _, layerBytes ->
            if (field != 3 || layerBytes == null || foundWater) return@forEachField
            var layerName = ""
            val features = ArrayList<ByteArray>()
            val keys = ArrayList<String>()
            val values = ArrayList<String?>()
            var layerExtent = 4096
            MvtReader(layerBytes).forEachField { id, _, longVal, bytesVal ->
                when (id) {
                    1 -> if (bytesVal != null) layerName = bytesVal.decodeToString()
                    2 -> if (bytesVal != null) features.add(bytesVal)
                    3 -> if (bytesVal != null) keys.add(bytesVal.decodeToString())
                    4 -> if (bytesVal != null) {
                        var strVal: String? = null
                        MvtReader(bytesVal).forEachField { vKey, _, _, vBytes ->
                            if (vKey == 1 && vBytes != null) strVal = vBytes.decodeToString()
                        }
                        values.add(strVal)
                    }
                    5 -> if (longVal > 0L) layerExtent = longVal.toInt()
                }
            }
            if (layerName != "water") return@forEachField
            foundWater = true
            if (layerExtent <= 0) return@forEachField
            extent = layerExtent
            for (featBytes in features) {
                var tags: ByteArray? = null
                var type = 0L
                var geom: ByteArray? = null
                MvtReader(featBytes).forEachField { id, _, longVal, bytesVal ->
                    when (id) {
                        2 -> tags = bytesVal
                        3 -> type = longVal
                        4 -> geom = bytesVal
                    }
                }
                if (type == 3L && geom != null && isOceanFeature(tags, keys, values)) {
                    val rings = decodeMvtPolygonRings(geom!!)
                    if (rings.isNotEmpty()) polygons.add(rings)
                }
            }
        }
        if (!foundWater) return null
        return OceanMaskGeometry(extent, polygons)
    }

    private fun isOceanFeature(tags: ByteArray?, keys: List<String>, values: List<String?>): Boolean {
        if (tags == null) return false
        val reader = MvtReader(tags)
        while (!reader.done) {
            val kIdx = reader.varint().toInt()
            val vIdx = reader.varint().toInt()
            if (kIdx in keys.indices && keys[kIdx] == "class") {
                return vIdx in values.indices && values[vIdx] == "ocean"
            }
        }
        return false
    }

    private fun decodeMvtPolygonRings(geometry: ByteArray): List<FloatArray> {
        val reader = MvtReader(geometry)
        var x = 0
        var y = 0
        fun zigzag(v: Long): Int = ((v ushr 1) xor -(v and 1L)).toInt()
        val rings = ArrayList<FloatArray>()
        var current = FloatArray(16)
        var len = 0
        fun appendPt(px: Int, py: Int) {
            if (len + 2 > current.size) {
                current = current.copyOf(current.size * 2)
            }
            current[len++] = px.toFloat()
            current[len++] = py.toFloat()
        }
        while (!reader.done) {
            val header = reader.varint()
            val cmd = (header and 7L).toInt()
            val count = (header ushr 3).toInt()
            if (cmd == 7 && count == 1) {
                if (len >= 6) rings.add(current.copyOf(len))
                len = 0
                continue
            }
            if (count <= 0 || (cmd != 1 && cmd != 2)) break
            for (i in 0 until count) {
                x += zigzag(reader.varint())
                y += zigzag(reader.varint())
                if (cmd == 1 && len >= 6) {
                    rings.add(current.copyOf(len))
                    len = 0
                }
                appendPt(x, y)
            }
        }
        if (len >= 6) rings.add(current.copyOf(len))
        return rings
    }

    internal fun decodeOceanMask(pbfBytes: ByteArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val geometry = extractOceanPolygons(pbfBytes) ?: return null
        val mask = ByteArray(width * height)
        if (geometry.polygons.isEmpty()) return mask
        val config: Bitmap.Config? = Bitmap.Config.ARGB_8888
        val bmp: Bitmap? = if (config != null) runCatching { Bitmap.createBitmap(width, height, config) }.getOrNull() else null
        if (bmp != null) {
            try {
                val canvas = Canvas(bmp)
                val scaleX = width / geometry.extent.toFloat()
                val scaleY = height / geometry.extent.toFloat()
                canvas.scale(scaleX, scaleY)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.BLACK
                }
                val path = Path().apply { fillType = Path.FillType.WINDING }
                for (rings in geometry.polygons) {
                    for (pts in rings) {
                        if (pts.size < 6) continue
                        path.moveTo(pts[0], pts[1])
                        var i = 2
                        while (i + 1 < pts.size) {
                            path.lineTo(pts[i], pts[i + 1])
                            i += 2
                        }
                        path.close()
                    }
                }
                canvas.drawPath(path, paint)
                val pixels = IntArray(width * height)
                bmp.getPixels(pixels, 0, width, 0, 0, width, height)
                for (i in pixels.indices) {
                    mask[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
                }
            } finally {
                bmp.recycle()
            }
            return mask
        }
        // Pure-JVM scanline fallback when Android Bitmap is unavailable in local unit tests.
        val scaleX = geometry.extent / width.toDouble()
        val scaleY = geometry.extent / height.toDouble()
        for (row in 0 until height) {
            val sy = (row + 0.5) * scaleY
            for (col in 0 until width) {
                val sx = (col + 0.5) * scaleX
                if (pointInOceanPolygons(sx, sy, geometry.polygons)) {
                    mask[row * width + col] = 0xFF.toByte()
                }
            }
        }
        return mask
    }

    private fun pointInOceanPolygons(sx: Double, sy: Double, polygons: List<List<FloatArray>>): Boolean {
        var winding = 0
        for (rings in polygons) {
            for (pts in rings) {
                val n = pts.size / 2
                if (n < 3) continue
                var j = n - 1
                for (i in 0 until n) {
                    val x1 = pts[j * 2].toDouble()
                    val y1 = pts[j * 2 + 1].toDouble()
                    val x2 = pts[i * 2].toDouble()
                    val y2 = pts[i * 2 + 1].toDouble()
                    if (y1 <= sy) {
                        if (y2 > sy && ((x2 - x1) * (sy - y1) - (sx - x1) * (y2 - y1)) > 0.0) winding++
                    } else {
                        if (y2 <= sy && ((x2 - x1) * (sy - y1) - (sx - x1) * (y2 - y1)) < 0.0) winding--
                    }
                    j = i
                }
            }
        }
        return winding != 0
    }

    /** Ports `earth-worker.js` ocean depth tinting, bilinear atlas sampling, and coastline masking. */
    internal fun gradePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        z: Int,
        tileX: Int,
        tileY: Int,
        atlasWidth: Int,
        atlasHeight: Int,
        atlasPixels: IntArray?,
        oceanMask: ByteArray? = null,
    ) {
        val hasAtlas = atlasPixels != null && atlasWidth > 0 && atlasHeight > 0
        val hasMask = oceanMask != null && oceanMask.size == width * height
        val seaDeep = if (hasAtlas) NATURAL_DEEP else DEEP_OPTICAL
        val seaShallow = if (hasAtlas) NATURAL_SHALLOW else SHALLOW_OPTICAL
        val tilesCount = (1 shl z).toDouble()
        val atlasX0 = IntArray(if (hasAtlas) width else 0)
        val atlasX1 = IntArray(atlasX0.size)
        val atlasTx = DoubleArray(atlasX0.size)
        for (col in atlasX0.indices) {
            val fx = (((tileX + (col + 0.5) / width) / tilesCount) * atlasWidth) - 0.5
            val fxInt = floor(fx).toInt()
            atlasTx[col] = fx - fxInt
            atlasX0[col] = Math.floorMod(fxInt, atlasWidth)
            atlasX1[col] = (atlasX0[col] + 1) % atlasWidth
        }

        for (row in 0 until height) {
            var y0 = 0
            var y1 = 0
            var ty = 0.0
            if (hasAtlas) {
                val fy = (((tileY + (row + 0.5) / height) / tilesCount) * atlasHeight) - 0.5
                val fyInt = floor(fy).toInt()
                ty = fy - fyInt
                y0 = fyInt.coerceIn(0, atlasHeight - 1)
                y1 = (fyInt + 1).coerceIn(0, atlasHeight - 1)
            }
            val row0Offset = y0 * atlasWidth
            val row1Offset = y1 * atlasWidth
            val rowOffset = row * width

            for (col in 0 until width) {
                val idx = rowOffset + col
                val argb = pixels[idx]
                val r8 = (argb ushr 16) and 0xFF
                val g8 = (argb ushr 8) and 0xFF
                val b8 = argb and 0xFF

                val r = r8 / 255.0
                val g = g8 / 255.0
                val b = b8 / 255.0
                val lum = 0.3 * r + 0.59 * g + 0.11 * b

                var ocR = 0.0
                var ocG = 0.0
                var ocB = 0.0
                var depthCoverage = 0.0
                if (hasAtlas) {
                    val tx = atlasTx[col]
                    val x0 = atlasX0[col]
                    val x1 = atlasX1[col]

                    val p00 = atlasPixels!![row0Offset + x0]
                    val p10 = atlasPixels[row0Offset + x1]
                    val p01 = atlasPixels[row1Offset + x0]
                    val p11 = atlasPixels[row1Offset + x1]

                    val w00 = (1.0 - tx) * (1.0 - ty)
                    val w10 = tx * (1.0 - ty)
                    val w01 = (1.0 - tx) * ty
                    val w11 = tx * ty

                    val wa00 = w00 * ((p00 ushr 24) and 0xFF)
                    val wa10 = w10 * ((p10 ushr 24) and 0xFF)
                    val wa01 = w01 * ((p01 ushr 24) and 0xFF)
                    val wa11 = w11 * ((p11 ushr 24) and 0xFF)
                    val aSum = wa00 + wa10 + wa01 + wa11
                    depthCoverage = aSum / 255.0
                    if (aSum > 1e-4) {
                        val invA = 1.0 / aSum
                        ocR = (wa00 * ((p00 ushr 16) and 0xFF) + wa10 * ((p10 ushr 16) and 0xFF) +
                            wa01 * ((p01 ushr 16) and 0xFF) + wa11 * ((p11 ushr 16) and 0xFF)) * invA
                        ocG = (wa00 * ((p00 ushr 8) and 0xFF) + wa10 * ((p10 ushr 8) and 0xFF) +
                            wa01 * ((p01 ushr 8) and 0xFF) + wa11 * ((p11 ushr 8) and 0xFF)) * invA
                        ocB = (wa00 * (p00 and 0xFF) + wa10 * (p10 and 0xFF) +
                            wa01 * (p01 and 0xFF) + wa11 * (p11 and 0xFF)) * invA
                    }
                }

                val shelf: Double
                val depthMix: Double
                val opticalMix: Double
                val landMix: Double
                if (hasMask) {
                    val maskAlpha = (oceanMask!![idx].toInt() and 0xFF) / 255.0
                    val water = maskAlpha * smooth(0.78, 0.52, lum) * smooth(0.085, 0.035, max(r - b, g - b))
                    val teal = min(g - r, b - r)
                    shelf = smooth(0.025, 0.26, teal)
                    depthMix = water * depthCoverage * (1.0 - 0.85 * smooth(0.025, 0.15, teal))
                    opticalMix = water - depthMix
                    landMix = 1.0 - water
                } else {
                    val navy = smooth(0.0, 0.05, b - max(r, g * 0.92)) * smooth(0.42, 0.22, lum)
                    val cyan = smooth(0.04, 0.14, b - r) * smooth(0.02, 0.10, g - r) * smooth(0.65, 0.4, lum)
                    val dark = smooth(0.20, 0.09, lum) * smooth(-0.03, 0.03, b - r) * smooth(0.06, -0.02, g - b)
                    val water = max(navy, max(cyan, dark))
                    shelf = 0.6 * smooth(0.06, 0.30, g + 0.5 * b - 0.2)
                    depthMix = depthCoverage * max(water, 1.0 - smooth(0.25, 0.62, lum))
                    opticalMix = water * (1.0 - depthMix)
                    landMix = (1.0 - water) * (1.0 - depthMix)
                }

                val pr = POW_LUT[r8]
                val pg = POW_LUT[g8]
                val pb = POW_LUT[b8]
                val l2 = 0.3 * pr + 0.59 * pg + 0.11 * pb

                val outR = channelOut(seaDeep[0], seaShallow[0], shelf, opticalMix, l2, pr, LAND_TINT[0], PAPER_RGB[0], landMix, ocR, depthMix)
                val outG = channelOut(seaDeep[1], seaShallow[1], shelf, opticalMix, l2, pg, LAND_TINT[1], PAPER_RGB[1], landMix, ocG, depthMix)
                val outB = channelOut(seaDeep[2], seaShallow[2], shelf, opticalMix, l2, pb, LAND_TINT[2], PAPER_RGB[2], landMix, ocB, depthMix)
                pixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    private fun gradePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        z: Int,
        tileX: Int,
        tileY: Int,
        atlas: OceanAtlas?,
        oceanMask: ByteArray?,
    ) = gradePixels(
        pixels = pixels,
        width = width,
        height = height,
        z = z,
        tileX = tileX,
        tileY = tileY,
        atlasWidth = atlas?.width ?: 0,
        atlasHeight = atlas?.height ?: 0,
        atlasPixels = atlas?.pixels,
        oceanMask = oceanMask,
    )

    private fun channelOut(
        deep: Int,
        shallow: Int,
        shelf: Double,
        opticalMix: Double,
        l2: Double,
        powChan: Double,
        tint: Double,
        paper: Int,
        landMix: Double,
        atlasChan: Double,
        depthMix: Double,
    ): Int {
        val opticalSea = deep * (1.0 - shelf) + shallow * shelf
        val land = 255.0 * (l2 + (powChan - l2) * 0.82) * tint * 0.9 + paper * 0.1
        val depth = if (depthMix > 0.0) atlasChan * depthMix else 0.0
        return (opticalSea * opticalMix + land * landMix + depth).roundToInt().coerceIn(0, 255)
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Keeps the bundled Earth visible from the first frame and uses the same Swiss paper palette for streets.
     */
    fun buildWorldStyleJson(
        rawKey: String = BuildConfig.MAPTILER_KEY,
        paperHex: String = "#f7f6f2",
        includeStreets: Boolean = true,
    ): String {
        val key = rawKey.trim()
        val hasRemoteKey = key.isNotEmpty()

        val sources = JSONObject().apply {
            put(
                "earth-base",
                JSONObject().apply {
                    put("type", "raster")
                    put("tiles", JSONArray(listOf("asset://design/swiss-world/earth/0/0/0.jpg")))
                    put("tileSize", 512)
                    put("minzoom", 0)
                    put("maxzoom", 0)
                },
            )
            put(
                "earth",
                JSONObject().apply {
                    put("type", "raster")
                    put("tiles", JSONArray(listOf("asset://design/swiss-world/earth/{z}/{x}/{y}.jpg")))
                    put("tileSize", 512)
                    put("minzoom", 0)
                    put("maxzoom", 3)
                },
            )
            if (hasRemoteKey) {
                put(
                    "earth-hd",
                    JSONObject().apply {
                        put("type", "raster")
                        put("tiles", JSONArray(listOf("https://$GRADED_EARTH_HOST/v2/{z}/{x}/{y}.jpg")))
                        put("tileSize", 256)
                        put("minzoom", 3)
                        put("maxzoom", 9)
                    },
                )
            }
            if (includeStreets) put("streets", JSONObject().put("type", "vector")
                .put("url", "mapbox://mapbox.mapbox-streets-v8"))
            WorldMapboxPins.appendSources(this)
        }

        val layers = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "ground")
                    put("type", "background")
                    put(
                        "paint",
                        JSONObject().apply {
                            put("background-color", "#ecebe4")
                            put("background-opacity", if (includeStreets) exprInterpolateLinearZoom(5.0 to 0.0, 8.0 to 1.0) else 0)
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("id", "earth-base")
                    put("type", "raster")
                    put("source", "earth-base")
                    put(
                        "paint",
                        JSONObject().apply {
                            put("raster-opacity", 1)
                            put("raster-fade-duration", 0)
                            put("raster-resampling", "linear")
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("id", "earth")
                    put("type", "raster")
                    put("source", "earth")
                    put(
                        "paint",
                        JSONObject().apply {
                            put("raster-opacity", 1)
                            put("raster-fade-duration", 0)
                            put("raster-resampling", "linear")
                        },
                    )
                },
            )
            if (hasRemoteKey) {
                put(
                    JSONObject().apply {
                        put("id", "earth-hd")
                        put("type", "raster")
                        put("source", "earth-hd")
                        put("minzoom", 1.25)
                        put("maxzoom", 8)
                        put(
                            "paint",
                            JSONObject().apply {
                                put(
                                    "raster-opacity",
                                    exprInterpolateLinearZoom(1.25 to 0.0, 1.75 to 1.0, 7.8 to 1.0, 8.0 to 0.0),
                                )
                                put("raster-fade-duration", 0)
                                put("raster-resampling", "linear")
                            },
                        )
                    },
                )
            }
            if (includeStreets) appendStreetLayers(this, paperHex)
            WorldMapboxPins.appendLayers(this)
        }

        return JSONObject().apply {
            put("version", 8)
            put("projection", JSONObject().put("name", "globe"))
            put("glyphs", "mapbox://fonts/mapbox/{fontstack}/{range}.pbf")
            put("sources", sources)
            put("layers", layers)
        }.toString()
    }

    private fun appendStreetLayers(target: JSONArray, paperHex: String) {
        val fade = exprInterpolateLinearZoom(5.0 to 0.0, 8.0 to 1.0)
        fun fillLayer(id: String, sourceLayer: String, color: String, filter: JSONArray? = null, minzoom: Int = 5) {
            target.put(
                JSONObject().apply {
                    put("id", id)
                    put("type", "fill")
                    put("source", "streets")
                    put("source-layer", sourceLayer)
                    put("minzoom", minzoom)
                    if (filter != null) put("filter", filter)
                    put("paint", JSONObject().put("fill-color", color).put("fill-opacity", fade))
                },
            )
        }
        fun lineLayer(id: String, sourceLayer: String, color: String, width: Any, filter: JSONArray? = null, minzoom: Int = 5) {
            target.put(
                JSONObject().apply {
                    put("id", id)
                    put("type", "line")
                    put("source", "streets")
                    put("source-layer", sourceLayer)
                    put("minzoom", minzoom)
                    if (filter != null) put("filter", filter)
                    put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
                    put("paint", JSONObject().put("line-color", color).put("line-width", width).put("line-opacity", fade))
                },
            )
        }

        fillLayer("world-landuse", "landuse", "#dee2cf", exprInClass(
            "park", "cemetery", "pitch", "recreation_ground", "garden", "wood", "grass", "agriculture",
        ))
        fillLayer("world-water", "water", "#b9d6dd")
        lineLayer("world-waterway", "waterway", "#b9d6dd", exprExpZoom(1.4, 8 to 0.5, 15 to 3.0, 18 to 10.0))
        fillLayer("world-building", "building", "#dfddd4", minzoom = 12)
        lineLayer("world-building-line", "building", "#cfccc1", 0.4, minzoom = 14)
        lineLayer("world-rail", "road", "#c3c1b7", exprExpZoom(1.4, 9 to 0.3, 14 to 1.2, 18 to 4.0), exprEqClass("rail"))
        val notMinor = exprNotInClass("rail", "path", "track")
        lineLayer("world-road-casing", "road", "#cfccc1", exprExpZoom(1.55, 9 to 1.1, 13 to 3.0, 16 to 7.0, 18 to 16.0), notMinor, minzoom = 9)
        lineLayer("world-road", "road", "#fdfcf8", exprExpZoom(1.55, 9 to 0.7, 13 to 2.4, 16 to 6.0, 18 to 15.0), notMinor, minzoom = 9)
        lineLayer("world-path", "road", "#fdfcf8", exprInterpolateLinearZoom(12.0 to 0.5, 18.0 to 3.0), exprInClass("path", "track"), minzoom = 12)
        target.put(
            JSONObject().apply {
                put("id", "world-street-name")
                put("type", "symbol")
                put("source", "streets")
                put("source-layer", "road")
                put("minzoom", 13)
                put(
                    "layout",
                    JSONObject().apply {
                        put("text-field", JSONArray().apply {
                            put("coalesce")
                            put(JSONArray(listOf("get", "name_zh-Hans")))
                            put(JSONArray(listOf("get", "name")))
                            put("")
                        })
                        put("text-font", JSONArray(listOf("DIN Pro Regular", "Arial Unicode MS Regular")))
                        put("text-size", 9.0)
                        put("symbol-placement", "line")
                        put("text-max-angle", 25)
                        put("text-padding", 8)
                    },
                )
                put(
                    "paint",
                    JSONObject().apply {
                        put("text-color", "#92958b")
                        put("text-halo-color", paperHex)
                        put("text-halo-width", 1.5)
                        put("text-opacity", exprInterpolateLinearZoom(13.0 to 0.0, 14.0 to 1.0))
                    },
                )
            },
        )
    }

    private fun exprInterpolateLinearZoom(vararg stops: Pair<Double, Double>): JSONArray = JSONArray().apply {
        put("interpolate")
        put(JSONArray(listOf("linear")))
        put(JSONArray(listOf("zoom")))
        for ((z, v) in stops) {
            put(z)
            put(v)
        }
    }

    private fun exprExpZoom(base: Double, vararg stops: Pair<Int, Double>): JSONArray = JSONArray().apply {
        put("interpolate")
        put(JSONArray(listOf("exponential", base)))
        put(JSONArray(listOf("zoom")))
        for ((z, v) in stops) {
            put(z)
            put(v)
        }
    }

    private fun exprInClass(vararg values: String): JSONArray = JSONArray().apply {
        put("in")
        put(JSONArray(listOf("get", "class")))
        put(JSONArray(listOf("literal", JSONArray(values.toList()))))
    }

    private fun exprNotInClass(vararg values: String): JSONArray = JSONArray().apply {
        put("!")
        put(exprInClass(*values))
    }

    private fun exprEqClass(value: String): JSONArray = JSONArray().apply {
        put("==")
        put(JSONArray(listOf("get", "class")))
        put(value)
    }
}
