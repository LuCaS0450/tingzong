package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cn.musicpilgrimage.app.BuildConfig
import java.net.URLEncoder
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Native MapLibre tile pipeline, offline asset style builder, and satellite color-grading proxy.
 * Replaces WebView `WorldResources` and `earth-worker.js` with zero JS/Chromium overhead.
 */
internal object WorldTilePipeline {
    private const val MAPTILER_HOST = "api.maptiler.com"
    private const val GRADED_EARTH_HOST = "earth-grade.tingzong.local"
    private val USER_AGENT =
        "cn.musicpilgrimage.app/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; MapLibre Android)"

    private val TILE_PATH_REGEX = Regex("^/(\\d+)/(\\d+)/(\\d+)\\.jpg$")
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
    private var cachedUpstream: OkHttpClient? = null

    @Volatile
    private var cachedOceanAtlas: OceanAtlas? = null

    @Volatile
    private var lastNetworkFailureAtMs: Long = 0L

    @Volatile
    private var lastStreetFailureAtMs: Long = 0L

    @Volatile
    private var lastHdFailureAtMs: Long = 0L

    @Volatile
    private var httpClientConfigured: Boolean = false

    private class OceanAtlas(val width: Int, val height: Int, val pixels: IntArray)

    @Synchronized
    fun configureMapLibre(context: Context) {
        val appContext = context.applicationContext
        if (!MapLibre.hasInstance()) {
            MapLibre.getInstance(appContext)
        }
        if (!httpClientConfigured) {
            HttpRequestUtil.setOkHttpClient(getClient(appContext))
            httpClientConfigured = true
        }
    }

    fun hasKey(rawKey: String = BuildConfig.MAPTILER_KEY): Boolean = rawKey.trim().isNotEmpty()

    fun recentStreetErrorSince(sinceMs: Long): Boolean = lastStreetFailureAtMs > sinceMs
    fun recentHdErrorSince(sinceMs: Long): Boolean = lastHdFailureAtMs > sinceMs

    fun resetErrorFlags() {
        lastNetworkFailureAtMs = 0L
        lastStreetFailureAtMs = 0L
        lastHdFailureAtMs = 0L
    }

    @Synchronized
    fun getClient(context: Context): OkHttpClient {
        cachedClient?.let { return it }
        val appContext = context.applicationContext
        val upstream = getUpstreamClient()
        val cacheDir = File(appContext.cacheDir, "world-map-tiles")
        val gradedCacheDir = File(cacheDir, "graded-earth")
        val client = OkHttpClient.Builder()
            .cache(Cache(cacheDir, 48L * 1024 * 1024))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addInterceptor(GradedEarthInterceptor(appContext, upstream, gradedCacheDir))
            .addInterceptor(OfflineFallbackInterceptor())
            .build()
        cachedClient = client
        return client
    }

    @Synchronized
    private fun getUpstreamClient(): OkHttpClient {
        cachedUpstream?.let { return it }
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
            .also { cachedUpstream = it }
    }

    private fun loadOceanAtlas(context: Context): OceanAtlas? {
        cachedOceanAtlas?.let { return it }
        synchronized(this) {
            cachedOceanAtlas?.let { return it }
            val loaded = runCatching {
                context.assets.open("design/swiss-world/ocean-colors.png").use { stream ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
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

    private class OfflineFallbackInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val req = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            val host = req.url.host
            return try {
                val response = chain.proceed(req)
                if (response.isSuccessful) {
                    if (host == GRADED_EARTH_HOST) lastHdFailureAtMs = 0L
                    else if (host == MAPTILER_HOST) lastStreetFailureAtMs = 0L
                } else if (response.code >= 500) {
                    if (host == GRADED_EARTH_HOST) lastHdFailureAtMs = System.currentTimeMillis()
                    else if (host == MAPTILER_HOST) lastStreetFailureAtMs = System.currentTimeMillis()
                }
                response
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                // Attempt stale disk cache so revisited streets and earth tiles remain usable offline.
                val cachedReq = req.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
                val cachedResp = runCatching { chain.proceed(cachedReq) }.getOrNull()
                if (cachedResp != null && cachedResp.isSuccessful) {
                    if (host == GRADED_EARTH_HOST) lastHdFailureAtMs = 0L
                    else if (host == MAPTILER_HOST) lastStreetFailureAtMs = 0L
                    cachedResp
                } else {
                    cachedResp?.close()
                    if (host == GRADED_EARTH_HOST) lastHdFailureAtMs = System.currentTimeMillis()
                    else if (host == MAPTILER_HOST) lastStreetFailureAtMs = System.currentTimeMillis()
                    lastNetworkFailureAtMs = System.currentTimeMillis()
                    syntheticError(req, 504, "Offline")
                }
            }
        }
    }

    private class GradedEarthInterceptor(
        private val appContext: Context,
        private val upstream: OkHttpClient,
        private val gradedCacheDir: File,
    ) : Interceptor {
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
            val satelliteUrl = "https://$MAPTILER_HOST/tiles/satellite-v2/$z/$x/$y.jpg?key=${URLEncoder.encode(key, "UTF-8")}"
            val upstreamReq = Request.Builder()
                .url(satelliteUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            return try {
                upstream.newCall(upstreamReq).execute().use { upstreamResp ->
                    val rawBytes = upstreamResp.body?.bytes()
                    if (!upstreamResp.isSuccessful || rawBytes == null || rawBytes.isEmpty()) {
                        lastHdFailureAtMs = System.currentTimeMillis()
                        return syntheticError(req, 503, "Tile upstream unavailable")
                    }
                    val graded = gradeSatelliteBytes(appContext, rawBytes, z, x, y) ?: rawBytes
                    persistGradedTile(tileFile, z, x, y, graded)
                    lastHdFailureAtMs = 0L
                    okTileResponse(req, graded)
                }
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                lastHdFailureAtMs = System.currentTimeMillis()
                syntheticError(req, 504, "Offline")
            }
        }

        private fun okTileResponse(req: Request, bytes: ByteArray): Response =
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "public, max-age=2592000")
                .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                .build()

        private fun persistGradedTile(tileFile: File, z: Int, x: Int, y: Int, bytes: ByteArray) {
            runCatching {
                gradedCacheDir.mkdirs()
                val tmp = File(gradedCacheDir, ".tmp_${z}_${x}_${y}_${System.nanoTime()}")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(tileFile)) {
                    tileFile.writeBytes(bytes)
                    tmp.delete()
                }
                val cached = gradedCacheDir.listFiles { f -> f.isFile && !f.name.startsWith(".tmp_") }
                if (cached != null && cached.size > 400) {
                    cached.sortBy { it.lastModified() }
                    val pruneCount = cached.size - 320
                    for (i in 0 until pruneCount) {
                        cached[i].delete()
                    }
                }
            }
        }
    }

    private fun syntheticError(req: Request, code: Int, message: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .header("Cache-Control", "no-store")
            .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
            .build()

    private fun gradeSatelliteBytes(
        context: Context,
        rawBytes: ByteArray,
        z: Int,
        tileX: Int,
        tileY: Int,
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
            gradePixels(pixels, w, h, z, tileX, tileY, atlas)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            val out = ByteArrayOutputStream(rawBytes.size)
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    /** Ports `earth-worker.js` ocean depth tinting and Swiss paper-warm land grading to native pixels. */
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
    ) {
        val hasAtlas = atlasPixels != null && atlasWidth > 0 && atlasHeight > 0
        val seaDeep = if (hasAtlas) NATURAL_DEEP else DEEP_OPTICAL
        val seaShallow = if (hasAtlas) NATURAL_SHALLOW else SHALLOW_OPTICAL
        val tilesCount = (1 shl z).toDouble()

        for (row in 0 until height) {
            val atlasY = if (hasAtlas) {
                (((tileY + (row + 0.5) / height) / tilesCount) * atlasHeight)
                    .toInt().coerceIn(0, atlasHeight - 1)
            } else 0
            val atlasRowOffset = atlasY * atlasWidth
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

                var oceanColor = 0
                var depthCoverage = 0.0
                if (hasAtlas) {
                    val atlasX = (((tileX + (col + 0.5) / width) / tilesCount) * atlasWidth)
                        .toInt().coerceIn(0, atlasWidth - 1)
                    oceanColor = atlasPixels!![atlasRowOffset + atlasX]
                    depthCoverage = ((oceanColor ushr 24) and 0xFF) / 255.0
                }

                val navy = smooth(0.0, 0.05, b - max(r, g * 0.92)) * smooth(0.42, 0.22, lum)
                val cyan = smooth(0.04, 0.14, b - r) * smooth(0.02, 0.10, g - r) * smooth(0.65, 0.4, lum)
                val dark = smooth(0.20, 0.09, lum) * smooth(-0.03, 0.03, b - r) * smooth(0.06, -0.02, g - b)
                val water = max(navy, max(cyan, dark))
                val shelf = 0.6 * smooth(0.06, 0.30, g + 0.5 * b - 0.2)
                val depthMix = depthCoverage * max(water, 1.0 - smooth(0.25, 0.62, lum))
                val opticalMix = water * (1.0 - depthMix)
                val landMix = (1.0 - water) * (1.0 - depthMix)

                val pr = POW_LUT[r8]
                val pg = POW_LUT[g8]
                val pb = POW_LUT[b8]
                val l2 = 0.3 * pr + 0.59 * pg + 0.11 * pb

                val ocR = (oceanColor ushr 16) and 0xFF
                val ocG = (oceanColor ushr 8) and 0xFF
                val ocB = oceanColor and 0xFF

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
        atlasChan: Int,
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
     * Builds the complete MapLibre Style JSON matching the Swiss paper palette and projection.
     * Bundled `asset://` earth tiles always load offline; remote HD earth & vector streets attach when key is present.
     */
    fun buildWorldStyleJson(
        rawKey: String = BuildConfig.MAPTILER_KEY,
        paperHex: String = "#f7f6f2",
    ): String {
        val key = rawKey.trim()
        val encodedKey = URLEncoder.encode(key, "UTF-8")
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
                        put("tiles", JSONArray(listOf("https://$GRADED_EARTH_HOST/{z}/{x}/{y}.jpg")))
                        put("tileSize", 256)
                        put("minzoom", 3)
                        put("maxzoom", 9)
                    },
                )
                put(
                    "streets",
                    JSONObject().apply {
                        put("type", "vector")
                        put("url", "https://$MAPTILER_HOST/tiles/v3/tiles.json?key=$encodedKey")
                    },
                )
            }
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
                            put("background-opacity", exprInterpolateLinearZoom(4.0 to 0.0, 7.0 to 1.0))
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
                            put("raster-opacity", exprInterpolateLinearZoom(5.0 to 1.0, 8.0 to 0.0))
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
                            put("raster-opacity", exprInterpolateLinearZoom(5.0 to 1.0, 8.0 to 0.0))
                            put("raster-fade-duration", 200)
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
                                    exprInterpolateLinearZoom(1.25 to 0.0, 1.75 to 1.0, 5.0 to 1.0, 8.0 to 0.0),
                                )
                                put("raster-fade-duration", 200)
                                put("raster-resampling", "linear")
                            },
                        )
                    },
                )
                appendStreetLayers(this, paperHex)
            }
        }

        return JSONObject().apply {
            put("version", 8)
            put("projection", JSONObject().put("type", "globe"))
            if (hasRemoteKey) {
                put("glyphs", "https://$MAPTILER_HOST/fonts/{fontstack}/{range}.pbf?key=$encodedKey")
            }
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

        fillLayer("world-park", "landcover", "#dee2cf", exprInClass("wood", "grass", "farmland"))
        fillLayer("world-landuse", "landuse", "#dee2cf", exprInClass("park", "cemetery", "pitch", "recreation_ground", "garden"))
        fillLayer("world-water", "water", "#b9d6dd")
        lineLayer("world-waterway", "waterway", "#b9d6dd", exprExpZoom(1.4, 8 to 0.5, 15 to 3.0, 18 to 10.0))
        fillLayer("world-building", "building", "#dfddd4", minzoom = 12)
        lineLayer("world-building-line", "building", "#cfccc1", 0.4, minzoom = 14)
        lineLayer("world-rail", "transportation", "#c3c1b7", exprExpZoom(1.4, 9 to 0.3, 14 to 1.2, 18 to 4.0), exprEqClass("rail"))
        val notMinor = exprNotInClass("rail", "path", "track")
        lineLayer("world-road-casing", "transportation", "#cfccc1", exprExpZoom(1.55, 9 to 1.1, 13 to 3.0, 16 to 7.0, 18 to 16.0), notMinor, minzoom = 9)
        lineLayer("world-road", "transportation", "#fdfcf8", exprExpZoom(1.55, 9 to 0.7, 13 to 2.4, 16 to 6.0, 18 to 15.0), notMinor, minzoom = 9)
        lineLayer("world-path", "transportation", "#fdfcf8", exprInterpolateLinearZoom(12.0 to 0.5, 18.0 to 3.0), exprInClass("path", "track"), minzoom = 12)
        target.put(
            JSONObject().apply {
                put("id", "world-street-name")
                put("type", "symbol")
                put("source", "streets")
                put("source-layer", "transportation_name")
                put("minzoom", 13)
                put(
                    "layout",
                    JSONObject().apply {
                        put("text-field", JSONArray().apply {
                            put("coalesce")
                            put(JSONArray(listOf("get", "name")))
                            put(JSONArray(listOf("get", "name:latin")))
                            put("")
                        })
                        put("text-font", JSONArray(listOf("Noto Sans Regular")))
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
