package cn.musicpilgrimage.app.performance

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class PerformanceCatalogSyncTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class BuiltTrack(
        val id: String,
        val version: Int,
        val minSchemaVersion: Int,
        val sortOrder: Int,
        val chartBytes: ByteArray,
        val chartSha: String,
        val opusBytes: ByteArray,
        val opusSha: String,
        val sourceSha: String,
    )

    private fun buildTrack(
        id: String,
        version: Int,
        title: String,
        sortOrder: Int,
        minSchemaVersion: Int = 1,
        releaseRhythmVerified: Boolean = true,
        sourceSeed: String = "source-$id",
        opusSeed: String = "opus-$id",
        noteOffsetUs: Long = 1_000_000L,
    ): BuiltTrack {
        val sourceBytes = sourceSeed.toByteArray(Charsets.UTF_8)
        val sourceSha = sha256(sourceBytes)
        val opusBytes = (opusSeed.repeat(64)).toByteArray(Charsets.UTF_8)
        val opusSha = sha256(opusBytes)
        val songJson = JSONObject().apply {
            put("id", id)
            put("version", version)
            put("title", title)
            put("artist", "测试艺人")
            put("referenceEdition", "测试版")
            put("releaseRhythmVerified", releaseRhythmVerified)
            put("durationUs", 3_000_000L)
            put("events", JSONArray())
            put("placeIds", JSONArray(listOf("place-$id")))
            put("sources", JSONObject().put("chartKind", "release-accent-rhythm"))
            put(
                "recording",
                JSONObject().apply {
                    put("sampleRate", 44100)
                    put("storage", "local")
                    put("encoding", "pcm16-delta")
                    put("frames", 132300L)
                    put("variants", JSONArray().put(JSONObject().put("speed", 1.0).put("file", "original.pcmz")))
                    put(
                        "audioFiles",
                        JSONArray().put(
                            JSONObject()
                                .put("file", "original.pcmz")
                                .put("bytes", sourceBytes.size.toLong())
                                .put("sha256", sourceSha)
                        )
                    )
                },
            )
            put(
                "charts",
                JSONObject().put(
                    "standard",
                    JSONArray()
                        .put(JSONObject().put("onsetUs", noteOffsetUs).put("durationUs", 160_000L).put("lane", 0).put("hold", false))
                        .put(JSONObject().put("onsetUs", noteOffsetUs + 500_000L).put("durationUs", 750_000L).put("lane", 1).put("hold", true)),
                ),
            )
            put(
                "sections",
                JSONArray().put(
                    JSONObject().put("name", "测试段").put("startUs", 0L).put("endUs", 3_000_000L).put("scene", "hall").put("bpm", 80.0)
                ),
            )
        }
        val chartBytes = songJson.toString().toByteArray(Charsets.UTF_8)
        return BuiltTrack(
            id = id,
            version = version,
            minSchemaVersion = minSchemaVersion,
            sortOrder = sortOrder,
            chartBytes = chartBytes,
            chartSha = sha256(chartBytes),
            opusBytes = opusBytes,
            opusSha = opusSha,
            sourceSha = sourceSha,
        )
    }

    private fun buildManifestBytes(revision: Int, tracks: List<BuiltTrack>): ByteArray {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("version", t.version)
                    put("minSchemaVersion", t.minSchemaVersion)
                    put("sortOrder", t.sortOrder)
                    put(
                        "chart",
                        JSONObject()
                            .put("object", "charts/${t.chartSha}.json")
                            .put("bytes", t.chartBytes.size.toLong())
                            .put("sha256", t.chartSha),
                    )
                    put(
                        "opus",
                        JSONObject()
                            .put("object", "excerpt/${t.opusSha}.ogg")
                            .put("bytes", t.opusBytes.size.toLong())
                            .put("sha256", t.opusSha)
                            .put("sourceSha256", t.sourceSha),
                    )
                }
            )
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("catalogRevision", revision)
            .put("updatedAt", "2026-11-25T12:00:00Z")
            .put("tracks", arr)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private class MockCatalogServer(
        @Volatile var manifestBytes: ByteArray,
        @Volatile var etag: String,
        val charts: MutableMap<String, ByteArray>,
    ) : AutoCloseable {
        val manifestRequests = AtomicInteger(0)
        val chartRequests = AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/catalog") { exchange ->
                try {
                    val path = exchange.requestURI.path.removePrefix("/catalog/")
                    if (path == "manifest.json") {
                        manifestRequests.incrementAndGet()
                        val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                        if (ifNoneMatch != null && ifNoneMatch == etag) {
                            exchange.sendResponseHeaders(304, -1)
                            return@createContext
                        }
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.responseHeaders.add("ETag", etag)
                        exchange.sendResponseHeaders(200, manifestBytes.size.toLong())
                        exchange.responseBody.write(manifestBytes)
                    } else if (path.startsWith("charts/")) {
                        chartRequests.incrementAndGet()
                        val payload = charts[path]
                        if (payload == null) {
                            exchange.sendResponseHeaders(404, -1)
                        } else {
                            exchange.responseHeaders.add("Content-Type", "application/json")
                            exchange.sendResponseHeaders(200, payload.size.toLong())
                            exchange.responseBody.write(payload)
                        }
                    } else {
                        exchange.sendResponseHeaders(404, -1)
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/catalog"

        override fun close() {
            server.stop(0)
        }
    }

    @Test
    fun coldSync_etag304_incrementalUpdate_andAudioReuseAcrossChartVersionBump() {
        val t1 = buildTrack("song-alpha", version = 1, title = "第一首", sortOrder = 1)
        val t2 = buildTrack("song-beta", version = 1, title = "第二首", sortOrder = 0)
        val catalogRoot = temporary.newFolder("catalog")
        val audioRoot = temporary.newFolder("audio")
        val store = PerformanceCatalogStore(catalogRoot)
        val audioStore = PerformanceAudioStore(audioRoot)

        MockCatalogServer(
            manifestBytes = buildManifestBytes(1, listOf(t1, t2)),
            etag = "\"rev-1\"",
            charts = mutableMapOf(
                "charts/${t1.chartSha}.json" to t1.chartBytes,
                "charts/${t2.chartSha}.json" to t2.chartBytes,
            ),
        ).use { mock ->
            val sync = PerformanceCatalogSync(store, mock.baseUrl, OkHttpClient(), requireHttps = false)

            // 1. Cold start syncs both charts and orders by sortOrder (beta before alpha).
            val first = sync.sync() as CatalogSyncOutcome.Updated
            assertEquals(1, first.revision)
            assertEquals(2, first.downloadedCharts)
            assertEquals(listOf("song-beta", "song-alpha"), first.songs.map { it.id })
            assertEquals(t1.opusSha, first.songs.single { it.id == "song-alpha" }.recording?.opus?.sha256)
            assertEquals(2, mock.chartRequests.get())

            // Install audio for song-alpha v1 using content-addressed blobs/.
            val alphaV1 = first.songs.single { it.id == "song-alpha" }
            val partFile = audioStore.partial(alphaV1).apply { writeBytes(t1.opusBytes) }
            audioStore.installDownloaded(alphaV1, partFile)
            assertNotNull(audioStore.verifiedDownloadDirectory(alphaV1))

            // 2. Second sync hits ETag 304 with zero chart downloads.
            assertEquals(CatalogSyncOutcome.Unchanged, sync.sync())
            assertEquals(2, mock.chartRequests.get())

            // 3. Chart revision on song-alpha (v1 -> v2, same opus audio) + new track song-gamma.
            val t1v2 = buildTrack(
                id = "song-alpha",
                version = 2,
                title = "第一首",
                sortOrder = 0,
                sourceSeed = "source-song-alpha",
                opusSeed = "opus-song-alpha",
                noteOffsetUs = 1_100_000L,
            )
            val t3 = buildTrack("song-gamma", version = 1, title = "第三首", sortOrder = 2)
            mock.charts["charts/${t1v2.chartSha}.json"] = t1v2.chartBytes
            mock.charts["charts/${t3.chartSha}.json"] = t3.chartBytes
            mock.manifestBytes = buildManifestBytes(2, listOf(t1v2, t2, t3))
            mock.etag = "\"rev-2\""

            val second = sync.sync() as CatalogSyncOutcome.Updated
            assertEquals(2, second.revision)
            // Only t1v2 and t3 were downloaded; unchanged t2 was reused from cache.
            assertEquals(2, second.downloadedCharts)
            assertEquals(4, mock.chartRequests.get())
            assertEquals(listOf("song-alpha", "song-beta", "song-gamma"), second.songs.map { it.id })

            // Audio cached under v1 is immediately reused by v2 with zero redownload.
            val alphaV2 = second.songs.single { it.id == "song-alpha" }
            assertEquals(2, alphaV2.version)
            assertEquals(1_100_000L, alphaV2.standard.first().onsetUs)
            assertNotNull(audioStore.verifiedDownloadDirectory(alphaV2))
            // Old chart file for t1 v1 was pruned.
            assertFalse(store.chartFile(t1.chartSha).exists())

            // 4. Retire song-alpha and verify unused chart + opus blob get pruned.
            mock.manifestBytes = buildManifestBytes(3, listOf(t2, t3))
            mock.etag = "\"rev-3\""
            val third = sync.sync() as CatalogSyncOutcome.Updated
            audioStore.pruneUnused(third.songs)
            assertEquals(listOf("song-beta", "song-gamma"), third.songs.map { it.id })
            assertFalse(store.chartFile(t1v2.chartSha).exists())
            assertFalse(audioStore.opusBlob(alphaV2.recording!!.opus!!).exists())
        }
    }

    @Test
    fun skipsIncompatibleFutureSchemaTracksWithoutBreakingOlderClients() {
        val current = buildTrack("song-now", version = 1, title = "当前支持", sortOrder = 0, minSchemaVersion = 1)
        val future = buildTrack("song-future", version = 1, title = "未来格式", sortOrder = 1, minSchemaVersion = 2)
        val store = PerformanceCatalogStore(temporary.newFolder("catalog-schema"))

        MockCatalogServer(
            manifestBytes = buildManifestBytes(1, listOf(current, future)),
            etag = "\"schema-1\"",
            charts = mutableMapOf("charts/${current.chartSha}.json" to current.chartBytes),
        ).use { mock ->
            val sync = PerformanceCatalogSync(store, mock.baseUrl, OkHttpClient(), requireHttps = false)
            val outcome = sync.sync() as CatalogSyncOutcome.Updated
            assertEquals(listOf("song-now"), outcome.songs.map { it.id })
            assertEquals(1, mock.chartRequests.get())
        }
    }

    @Test
    fun rejectsCorruptOrUnverifiedChartAndPreservesExistingCache() {
        val good = buildTrack("song-good", version = 1, title = "有效曲目", sortOrder = 0)
        val store = PerformanceCatalogStore(temporary.newFolder("catalog-corrupt"))

        MockCatalogServer(
            manifestBytes = buildManifestBytes(1, listOf(good)),
            etag = "\"good-1\"",
            charts = mutableMapOf("charts/${good.chartSha}.json" to good.chartBytes),
        ).use { mock ->
            val sync = PerformanceCatalogSync(store, mock.baseUrl, OkHttpClient(), requireHttps = false)
            sync.sync()
            assertEquals(listOf("song-good"), store.loadCachedSongs().map { it.id })

            // Tamper remote chart payload so sha256 fails.
            val bad = buildTrack("song-bad", version = 1, title = "损坏曲目", sortOrder = 1)
            mock.charts["charts/${bad.chartSha}.json"] = bad.chartBytes.copyOf().apply { this[0] = ' '.code.toByte() }
            mock.manifestBytes = buildManifestBytes(2, listOf(good, bad))
            mock.etag = "\"bad-2\""

            assertThrows(IllegalArgumentException::class.java) { sync.sync() }
            // Previously committed cache remains intact.
            assertEquals(listOf("song-good"), store.loadCachedSongs().map { it.id })
            assertEquals(1, store.readManifest()?.catalogRevision)

            // Also reject unverified rhythm packages even if sha256 matches.
            val unverified = buildTrack(
                "song-unverified",
                version = 1,
                title = "未校对曲目",
                sortOrder = 2,
                releaseRhythmVerified = false,
            )
            mock.charts["charts/${unverified.chartSha}.json"] = unverified.chartBytes
            mock.manifestBytes = buildManifestBytes(3, listOf(good, unverified))
            mock.etag = "\"bad-3\""

            assertThrows(IllegalArgumentException::class.java) { sync.sync() }
            assertEquals(listOf("song-good"), store.loadCachedSongs().map { it.id })
        }
    }
}
