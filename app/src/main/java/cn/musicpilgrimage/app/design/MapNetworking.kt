package cn.musicpilgrimage.app.design

import android.content.Context
import cn.musicpilgrimage.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The preview shares the configured map key with the application family. */
internal object MapNetworking {
    // Streets v2 copy using canonical Chinese names (e.g. Paris = 巴黎).
    const val referenceMapId = "01a12d28-64b9-7f65-bc76-b8d778fa8b5d"
    @Volatile private var chineseLabels: ChineseMapLabels? = null
    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val identified = if (request.url.host == "api.maptiler.com") {
                // The native cache distinguishes normalized labels; the provider receives its original URL.
                val url = request.url.newBuilder().removeAllQueryParameters(ChineseMapLabels.CACHE_PARAMETER).build()
                request.newBuilder().url(url).header("User-Agent",
                    "cn.musicpilgrimage.app/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; MapLibre Android)")
                    .build()
            } else request
            val response = chain.proceed(identified)
            val labels = chineseLabels
            val path = request.url.encodedPath
            val marked = request.url.queryParameter(ChineseMapLabels.CACHE_PARAMETER) != null
            val style = path == "/maps/$referenceMapId/style.json"
            if (labels == null || !response.isSuccessful || request.url.host != "api.maptiler.com" ||
                (!style && !marked)) response else {
                val body = response.body ?: return@addInterceptor response
                val original = body.bytes()
                val normalized = try {
                    val gzip = original.size >= 2 && original[0] == 0x1f.toByte() && original[1] == 0x8b.toByte()
                    val decoded = if (gzip) GZIPInputStream(ByteArrayInputStream(original)).use { it.readBytes() } else original
                    val converted = when {
                        style -> labels.style(decoded)
                        path.endsWith(".json") -> labels.tileMetadata(decoded)
                        path.endsWith(".pbf") -> labels.tile(decoded)
                        else -> decoded
                    }
                    if (gzip) ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(converted) } }.toByteArray()
                    else converted
                } catch (_: Exception) { original }
                response.newBuilder().removeHeader("Content-Length").body(normalized.toResponseBody(body.contentType())).build()
            }
        }.build()
    }

    // A single client serves both metadata and native tiles, including injected
    // transport failures in the private preview's instrumentation tests.
    var client: OkHttpClient = defaultClient

    fun configure(context: Context) {
        MapLibre.getInstance(context)
        if (chineseLabels == null) chineseLabels = ChineseMapLabels.load(context)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        HttpRequestUtil.setOkHttpClient(client)
    }
}
