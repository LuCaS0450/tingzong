package cn.musicpilgrimage.app.performance

import android.content.Context
import cn.musicpilgrimage.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class CloudChartRef(val objectPath: String, val bytes: Long, val sha256: String)

internal data class CloudOpusRef(
    val objectPath: String,
    val bytes: Long,
    val sha256: String,
    val sourceSha256: String,
)

internal data class CloudCatalogTrack(
    val id: String,
    val version: Int,
    val minSchemaVersion: Int,
    val sortOrder: Int,
    val chart: CloudChartRef,
    val opus: CloudOpusRef,
    val title: String = "",
    val artist: String = "",
    val durationUs: Long = 0L,
    val placeIds: List<String> = emptyList(),
    val workKey: String = "",
) {
    val hasDisplayMetadata: Boolean get() = title.isNotBlank() && artist.isNotBlank()
}

internal data class PerformanceCatalogItem(
    val id: String,
    val version: Int,
    val title: String,
    val artist: String,
    val durationUs: Long = 0L,
    val placeIds: List<String> = emptyList(),
    val workKey: String = "",
    val opusBytes: Long = 0L,
    val chartReady: Boolean = false,
    val audioReady: Boolean = false,
    val track: CloudCatalogTrack? = null,
    val entry: PerformanceEntry? = null,
)

internal fun PerformanceEntry.toCatalogItem(): PerformanceCatalogItem = PerformanceCatalogItem(
    id = song.id,
    version = song.version,
    title = song.title,
    artist = song.artist,
    durationUs = song.durationUs,
    placeIds = song.placeIds,
    opusBytes = song.recording?.downloadFile?.second?.bytes ?: 0L,
    chartReady = true,
    audioReady = audioReady,
    entry = this,
)

internal data class CloudCatalogManifest(
    val schemaVersion: Int,
    val catalogRevision: Int,
    val updatedAt: String,
    val tracks: List<CloudCatalogTrack>,
) {
    fun compatibleTracks(): List<CloudCatalogTrack> = tracks
        .filter { it.minSchemaVersion <= SUPPORTED_SCHEMA_VERSION }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
        const val MAX_CHART_BYTES = 2L * 1024 * 1024
        internal val ID_REGEX = Regex("[a-z0-9-]+")
        internal val SHA256_REGEX = Regex("[a-f0-9]{64}")

        fun from(json: JSONObject): CloudCatalogManifest {
            val schemaVersion = json.getInt("schemaVersion")
            val catalogRevision = json.getInt("catalogRevision")
            val updatedAt = json.optString("updatedAt", "")
            require(schemaVersion >= 1 && catalogRevision >= 1) { "曲库清单版本无效" }
            val rawTracks: JSONArray = json.getJSONArray("tracks")
            val seenIds = mutableSetOf<String>()
            val tracks = List(rawTracks.length()) { index ->
                val row = rawTracks.getJSONObject(index)
                val id = row.getString("id")
                val version = row.getInt("version")
                val minSchema = row.optInt("minSchemaVersion", 1)
                val sortOrder = row.optInt("sortOrder", index)
                require(ID_REGEX.matches(id) && version > 0 && minSchema >= 1) { "曲目条目无效: $id" }
                require(seenIds.add(id)) { "曲目清单存在重复 ID: $id" }

                val chartObj = row.getJSONObject("chart")
                val chartSha = chartObj.getString("sha256")
                val chartBytes = chartObj.getLong("bytes")
                val chartPath = chartObj.getString("object")
                require(
                    SHA256_REGEX.matches(chartSha) &&
                        chartBytes in 1..MAX_CHART_BYTES &&
                        chartPath == "charts/$chartSha.json"
                ) { "曲目谱面校验信息无效: $id" }

                val opusObj = row.getJSONObject("opus")
                val opusSha = opusObj.getString("sha256")
                val opusBytes = opusObj.getLong("bytes")
                val opusPath = opusObj.getString("object")
                val sourceSha = opusObj.getString("sourceSha256")
                require(
                    SHA256_REGEX.matches(opusSha) &&
                        SHA256_REGEX.matches(sourceSha) &&
                        opusBytes in 1..RecordedMusic.MAX_PCM_BYTES &&
                        opusPath == "excerpt/$opusSha.ogg"
                ) { "曲目音频校验信息无效: $id" }

                val title = row.optString("title", "").trim()
                val artist = row.optString("artist", "").trim()
                val durationUs = row.optLong("durationUs", 0L).coerceAtLeast(0L)
                val placeIds = row.optJSONArray("placeIds")?.let { arr ->
                    List(arr.length()) { arr.optString(it, "") }.filter(String::isNotBlank)
                } ?: emptyList()
                val workKey = row.optString("workKey", "").trim()

                CloudCatalogTrack(
                    id = id,
                    version = version,
                    minSchemaVersion = minSchema,
                    sortOrder = sortOrder,
                    chart = CloudChartRef(chartPath, chartBytes, chartSha),
                    opus = CloudOpusRef(opusPath, opusBytes, opusSha, sourceSha),
                    title = title,
                    artist = artist,
                    durationUs = durationUs,
                    placeIds = placeIds,
                    workKey = workKey,
                )
            }
            return CloudCatalogManifest(schemaVersion, catalogRevision, updatedAt, tracks)
        }
    }
}

internal sealed interface CatalogSyncOutcome {
    data object Unchanged : CatalogSyncOutcome
    data class Updated(
        val revision: Int,
        val songs: List<SongPackage>,
        val downloadedCharts: Int,
        val tracks: List<CloudCatalogTrack> = emptyList(),
    ) : CatalogSyncOutcome
}

internal class PerformanceCatalogStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "performance-catalog"))

    private val manifestFile = File(root, "manifest.json")
    private val etagFile = File(root, "manifest.etag")
    private val chartsDir = File(root, "charts")

    internal fun chartFile(sha256: String): File {
        require(CloudCatalogManifest.SHA256_REGEX.matches(sha256)) { "无效的谱面校验值" }
        return File(chartsDir, "$sha256.json")
    }

    fun hasValidChart(ref: CloudChartRef): Boolean {
        val file = chartFile(ref.sha256)
        if (!file.isFile || file.length() != ref.bytes) return false
        return sha256Of(file) == ref.sha256
    }

    fun readManifest(): CloudCatalogManifest? = synchronized(lock) {
        if (!manifestFile.isFile) return@synchronized null
        runCatching {
            CloudCatalogManifest.from(JSONObject(manifestFile.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun readEtag(): String? = synchronized(lock) {
        if (!etagFile.isFile) return@synchronized null
        runCatching { etagFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    fun writeChart(ref: CloudChartRef, bytes: ByteArray) = synchronized(lock) {
        require(bytes.size.toLong() == ref.bytes && sha256Of(bytes) == ref.sha256) { "谱面文件校验未通过" }
        atomicWrite(chartFile(ref.sha256), bytes)
    }

    fun commitManifest(manifestBytes: ByteArray, etag: String?) = synchronized(lock) {
        CloudCatalogManifest.from(JSONObject(String(manifestBytes, Charsets.UTF_8)))
        atomicWrite(manifestFile, manifestBytes)
        if (!etag.isNullOrBlank()) {
            atomicWrite(etagFile, etag.trim().toByteArray(Charsets.UTF_8))
        } else if (etagFile.exists()) {
            etagFile.delete()
        }
    }

    fun loadManifestTracks(): List<CloudCatalogTrack> = synchronized(lock) {
        readManifest()?.compatibleTracks().orEmpty()
    }

    fun loadCachedSong(track: CloudCatalogTrack): SongPackage? = synchronized(lock) {
        val file = chartFile(track.chart.sha256)
        if (!file.isFile || file.length() != track.chart.bytes) return@synchronized null
        runCatching {
            val raw = file.readBytes()
            require(sha256Of(raw) == track.chart.sha256) { "缓存谱面已损坏" }
            parseAndBindTrack(raw, track)
        }.getOrNull()
    }

    fun loadCachedSongById(id: String, version: Int? = null): SongPackage? = synchronized(lock) {
        val manifest = readManifest() ?: return@synchronized null
        val track = manifest.compatibleTracks().firstOrNull {
            it.id == id && (version == null || it.version == version)
        } ?: return@synchronized null
        loadCachedSong(track)
    }

    fun loadCachedSongs(): List<SongPackage> = synchronized(lock) {
        val manifest = readManifest() ?: return@synchronized emptyList()
        manifest.compatibleTracks().mapNotNull { track -> loadCachedSong(track) }
    }

    fun pruneUnused(activeChartHashes: Set<String>) = synchronized(lock) {
        val files = chartsDir.listFiles() ?: return@synchronized
        val activeNames = activeChartHashes.mapTo(mutableSetOf()) { "$it.json" }
        for (file in files) {
            if (file.isFile && file.name.endsWith(".json") && file.name !in activeNames) {
                file.delete()
            }
        }
    }

    fun clear() = synchronized(lock) {
        root.deleteRecursively()
    }

    companion object {
        private val lock = Any()

        internal fun parseAndBindTrack(chartBytes: ByteArray, track: CloudCatalogTrack): SongPackage {
            val song = SongPackage.from(JSONObject(String(chartBytes, Charsets.UTF_8)))
            require(song.id == track.id && song.version == track.version) {
                "谱面元数据与清单不匹配: ${track.id}"
            }
            val recording = requireNotNull(song.recording?.takeIf { it.local }) {
                "曲目缺少音源配置: ${track.id}"
            }
            require(recording.files.values.singleOrNull()?.sha256 == track.opus.sourceSha256) {
                "曲目音频来源校验不一致: ${track.id}"
            }
            val opusFile = RecordingFile(track.opus.bytes, track.opus.sha256)
            return song.copy(recording = recording.copy(opus = opusFile))
        }

        internal fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun sha256Of(file: File): String = file.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun atomicWrite(target: File, bytes: ByteArray) {
            val parent = requireNotNull(target.parentFile)
            check(parent.mkdirs() || parent.isDirectory) { "无法创建缓存目录: ${parent.absolutePath}" }
            val temp = File.createTempFile(".tmp-", ".part", parent)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                }
                try {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
        }
    }
}

internal object PerformanceHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

internal class PerformanceCatalogSync(
    private val store: PerformanceCatalogStore,
    private val catalogBaseUrl: String = defaultBaseUrl(),
    private val client: OkHttpClient = PerformanceHttp.client,
    private val requireHttps: Boolean = true,
) {
    private fun validatedBaseUrl(): String {
        val base = catalogBaseUrl.trimEnd('/')
        if (requireHttps) {
            check(base.startsWith("https://")) { "曲库服务尚未配置" }
        } else {
            check(base.startsWith("https://") || base.startsWith("http://")) { "曲库服务地址无效" }
        }
        return base
    }

    fun sync(force: Boolean = false, prefetchAllCharts: Boolean = false): CatalogSyncOutcome {
        val base = validatedBaseUrl()

        val cachedManifest = store.readManifest()
        val cachedTracks = cachedManifest?.compatibleTracks().orEmpty()
        val cachedUsable = cachedManifest != null && cachedTracks.isNotEmpty() && (
            (!prefetchAllCharts && cachedTracks.all { it.hasDisplayMetadata }) ||
                cachedTracks.all { store.hasValidChart(it.chart) }
            )
        val etag = if (!force && cachedUsable) store.readEtag() else null

        val manifestUrl = "$base/manifest.json"
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .apply {
                if (etag != null) header("If-None-Match", etag)
                if (force) header("Cache-Control", "no-cache")
            }
            .build()

        val manifestBytes: ByteArray
        val responseEtag: String?
        client.newCall(request).execute().use { response ->
            if (response.code == 304 && cachedUsable) {
                return CatalogSyncOutcome.Unchanged
            }
            if (!response.isSuccessful) {
                throw IOException("曲库清单同步失败 (HTTP ${response.code})")
            }
            manifestBytes = readBoundedBytes(
                checkNotNull(response.body).byteStream(),
                CloudCatalogManifest.MAX_MANIFEST_BYTES,
            )
            responseEtag = response.header("ETag")
        }

        val manifest = CloudCatalogManifest.from(JSONObject(String(manifestBytes, Charsets.UTF_8)))
        val compatible = manifest.compatibleTracks()
        val needsEagerCharts = prefetchAllCharts || compatible.any { !it.hasDisplayMetadata }
        var downloadedCharts = 0

        if (needsEagerCharts) {
            for (track in compatible) {
                if (store.hasValidChart(track.chart)) {
                    val cachedBytes = store.chartFile(track.chart.sha256).readBytes()
                    PerformanceCatalogStore.parseAndBindTrack(cachedBytes, track)
                } else {
                    val chartUrl = "$base/${track.chart.objectPath}"
                    val chartBytes = downloadChart(chartUrl, track.chart)
                    PerformanceCatalogStore.parseAndBindTrack(chartBytes, track)
                    store.writeChart(track.chart, chartBytes)
                    downloadedCharts += 1
                }
            }
        }

        store.commitManifest(manifestBytes, responseEtag)
        store.pruneUnused(compatible.mapTo(mutableSetOf()) { it.chart.sha256 })
        val songs = store.loadCachedSongs()
        return CatalogSyncOutcome.Updated(manifest.catalogRevision, songs, downloadedCharts, compatible)
    }

    fun ensureChart(track: CloudCatalogTrack): SongPackage {
        store.loadCachedSong(track)?.let { return it }
        val base = validatedBaseUrl()
        val chartUrl = "$base/${track.chart.objectPath}"
        val chartBytes = downloadChart(chartUrl, track.chart)
        val song = PerformanceCatalogStore.parseAndBindTrack(chartBytes, track)
        store.writeChart(track.chart, chartBytes)
        return song
    }

    private fun downloadChart(url: String, ref: CloudChartRef): ByteArray {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("谱面下载失败 (HTTP ${response.code})")
            }
            val bytes = readBoundedBytes(checkNotNull(response.body).byteStream(), ref.bytes)
            require(bytes.size.toLong() == ref.bytes) { "谱面文件大小与清单不符" }
            require(PerformanceCatalogStore.sha256Of(bytes) == ref.sha256) { "谱面文件校验未通过" }
            return bytes
        }
    }

    companion object {
        fun defaultBaseUrl(): String =
            BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/performance-catalog"

        private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            input.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "响应内容超出允许大小" }
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        }
    }
}
