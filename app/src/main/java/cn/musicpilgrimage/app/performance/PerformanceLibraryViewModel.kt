package cn.musicpilgrimage.app.performance

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.musicpilgrimage.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PerformanceLibraryUi(
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val items: List<PerformanceCatalogItem> = emptyList(),
    val entries: List<PerformanceEntry> = emptyList(),
    val importing: String? = null,
    val progress: Float = 0f,
    val notice: String? = null,
    val error: String? = null,
    val catalogError: String? = null,
    val downloads: Map<String, AudioDownloadState> = emptyMap(),
    val mobileConfirmationSongId: String? = null,
)

internal class PerformanceLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogStore = PerformanceCatalogStore(application)
    private val catalogSync = PerformanceCatalogSync(catalogStore)
    private val store = PerformanceAudioStore(application)
    private val mutable = MutableStateFlow(PerformanceLibraryUi())
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    private var mobileAllowed = false
    private var lastSuccessSyncAtMs = 0L
    private val checkedDownloads = mutableMapOf<java.util.UUID, java.io.File?>()
    private val preparingSongs = mutableSetOf<String>()

    private fun matches(work: WorkInfo, song: SongPackage): Boolean {
        val opus = PerformanceAudioDownloadWorker.opusTag(song)
        return "audio-song:${song.id}" in work.tags && "audio-version:${song.version}" in work.tags &&
            work.tags.firstOrNull { it.startsWith("audio-opus:") } == opus
    }

    init {
        viewModelScope.launch {
            val cached = loadFromLocalCache()
            if (cached.isNotEmpty()) {
                publishItems(cached, loaded = true, catalogError = null)
            }
            refreshCatalog(force = false)
        }
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosByTagFlow(PerformanceAudioDownloadWorker.TAG).collect { infos ->
                val old = mutable.value
                if (old.items.isEmpty()) return@collect
                val updatedItems = withContext(Dispatchers.IO) {
                    old.items.map { item ->
                        val entry = item.entry ?: return@map item
                        if (entry.downloadReady) return@map item
                        val completed = infos.filter { matches(it, entry.song) && it.state == WorkInfo.State.SUCCEEDED }
                        val directory = completed.firstNotNullOfOrNull { work ->
                            if (!checkedDownloads.containsKey(work.id)) {
                                checkedDownloads[work.id] = store.verifiedDownloadDirectory(entry.song)
                            }
                            checkedDownloads[work.id]
                        }
                        if (directory != null) {
                            val readyEntry = entry.copy(audioDirectory = directory, downloadReady = true)
                            item.copy(audioReady = readyEntry.audioReady, entry = readyEntry)
                        } else {
                            item
                        }
                    }
                }
                val workStates = buildMap {
                    for (item in updatedItems) {
                        val entry = item.entry ?: continue
                        if (entry.downloadReady) continue
                        val matching = infos.filter { matches(it, entry.song) && it.state != WorkInfo.State.CANCELLED }
                        val work = matching.firstOrNull { !it.state.isFinished } ?: matching.lastOrNull() ?: continue
                        val status = when (work.state) {
                            WorkInfo.State.RUNNING -> AudioDownloadState(
                                "正在准备 ${(work.progress.getFloat("fraction", 0f) * 100).toInt()}%",
                                work.progress.getFloat("fraction", 0f),
                            )
                            WorkInfo.State.FAILED -> AudioDownloadState("这首歌还没准备好", failed = true)
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> AudioDownloadState("音乐准备中")
                            else -> null
                        }
                        if (status != null) put(item.id, status)
                    }
                }
                // Preserve in-flight chart fetch states for songs currently being prepared, remove finished ones.
                val readyIds = updatedItems.filter { it.entry?.audioReady == true }.mapTo(mutableSetOf()) { it.id }
                val mergedDownloads = (mutable.value.downloads - readyIds) + workStates
                if (mutable.value.items == old.items) {
                    mutable.value = mutable.value.copy(
                        items = updatedItems,
                        entries = updatedItems.mapNotNull { it.entry },
                        downloads = mergedDownloads,
                    )
                }
            }
        }
    }

    private fun publishItems(
        items: List<PerformanceCatalogItem>,
        loaded: Boolean = mutable.value.loaded,
        syncing: Boolean = mutable.value.syncing,
        catalogError: String? = mutable.value.catalogError,
        notice: String? = mutable.value.notice,
        importing: String? = mutable.value.importing,
    ) {
        mutable.value = mutable.value.copy(
            loaded = loaded,
            syncing = syncing,
            items = items,
            entries = items.mapNotNull { it.entry },
            catalogError = catalogError,
            notice = notice,
            importing = importing,
        )
    }

    private suspend fun loadFromLocalCache(): List<PerformanceCatalogItem> = withContext(Dispatchers.IO) {
        val tracks = catalogStore.loadManifestTracks()
        if (tracks.isEmpty()) return@withContext emptyList()
        buildItems(tracks)
    }

    private fun buildEntry(song: SongPackage): PerformanceEntry {
        val downloaded = store.verifiedDownloadDirectory(song)
        val legacy = if (downloaded == null && song.recording?.opus != null) store.verifiedLegacyDirectory(song) else null
        return PerformanceEntry(
            song = song,
            audioDirectory = downloaded ?: legacy,
            downloadReady = downloaded != null || song.recording?.local != true,
        )
    }

    private fun buildItems(tracks: List<CloudCatalogTrack>): List<PerformanceCatalogItem> =
        tracks.mapNotNull { track ->
            val cachedSong = catalogStore.loadCachedSong(track)
            if (cachedSong != null) {
                val entry = buildEntry(cachedSong)
                PerformanceCatalogItem(
                    id = track.id,
                    version = track.version,
                    title = track.title.ifBlank { cachedSong.title },
                    artist = track.artist.ifBlank { cachedSong.artist },
                    durationUs = if (track.durationUs > 0L) track.durationUs else cachedSong.durationUs,
                    placeIds = track.placeIds.ifEmpty { cachedSong.placeIds },
                    workKey = track.workKey,
                    opusBytes = track.opus.bytes,
                    chartReady = true,
                    audioReady = entry.audioReady,
                    track = track,
                    entry = entry,
                )
            } else if (track.hasDisplayMetadata) {
                PerformanceCatalogItem(
                    id = track.id,
                    version = track.version,
                    title = track.title,
                    artist = track.artist,
                    durationUs = track.durationUs,
                    placeIds = track.placeIds,
                    workKey = track.workKey,
                    opusBytes = track.opus.bytes,
                    chartReady = false,
                    audioReady = store.verifiedOpusDirectory(track.opus) != null,
                    track = track,
                    entry = null,
                )
            } else {
                null
            }
        }

    fun onWallEntered() {
        val elapsed = SystemClock.elapsedRealtime() - lastSuccessSyncAtMs
        if (mutable.value.items.isEmpty() || lastSuccessSyncAtMs == 0L || elapsed >= SYNC_COOLDOWN_MS) {
            viewModelScope.launch { refreshCatalog(force = false) }
        }
    }

    fun retryCatalog() {
        viewModelScope.launch {
            if (mutable.value.items.isEmpty()) {
                mutable.value = mutable.value.copy(loaded = false, catalogError = null)
            }
            refreshCatalog(force = true)
        }
    }

    suspend fun refreshCatalog(force: Boolean = false) = syncMutex.withLock {
        mutable.value = mutable.value.copy(syncing = true)
        try {
            val outcome = withContext(Dispatchers.IO) { catalogSync.sync(force = force) }
            lastSuccessSyncAtMs = SystemClock.elapsedRealtime()
            when (outcome) {
                CatalogSyncOutcome.Unchanged -> {
                    val current = if (mutable.value.items.isEmpty()) loadFromLocalCache() else mutable.value.items
                    publishItems(current, loaded = true, syncing = false, catalogError = null)
                }
                is CatalogSyncOutcome.Updated -> {
                    val updatedItems = withContext(Dispatchers.IO) {
                        val tracks = outcome.tracks.ifEmpty { catalogStore.loadManifestTracks() }
                        store.pruneUnusedManifest(tracks)
                        buildItems(tracks)
                    }
                    checkedDownloads.clear()
                    publishItems(updatedItems, loaded = true, syncing = false, catalogError = null)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("PerformanceCatalog", "Cloud catalog sync skipped or failed", error)
            val fallbackItems = if (mutable.value.items.isEmpty()) loadFromLocalCache() else mutable.value.items
            publishItems(
                fallbackItems,
                loaded = true,
                syncing = false,
                catalogError = if (fallbackItems.isEmpty()) "曲库加载未完成，点此重试" else null,
            )
        }
    }

    /** Downloads and verifies the selected song's chart (~80 KB) and audio (~1.3 MB) in parallel over a warm HTTP/2 pool. */
    fun prepareSong(songId: String, retry: Boolean = false) {
        val currentItem = mutable.value.items.find { it.id == songId } ?: return
        if (currentItem.entry?.audioReady == true) return
        if (!retry && songId in preparingSongs) return

        val needsAudioDownload = !currentItem.audioReady
        if (needsAudioDownload) {
            val connectivity = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (connectivity.activeNetwork != null && connectivity.isActiveNetworkMetered && !mobileAllowed) {
                mutable.value = mutable.value.copy(mobileConfirmationSongId = songId)
                return
            }
        }

        preparingSongs.add(songId)
        if (retry) mutable.value = mutable.value.copy(error = null)

        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val track = currentItem.track
                mutable.value = mutable.value.copy(
                    downloads = mutable.value.downloads + (
                        songId to AudioDownloadState(
                            if (needsAudioDownload) "正在准备 0%" else "正在加载音乐",
                            if (needsAudioDownload) 0f else null,
                        )
                    ),
                )

                val (song, audioDir) = coroutineScope {
                    val chartJob = async(Dispatchers.IO) {
                        val t0 = SystemClock.elapsedRealtime()
                        val loaded = currentItem.entry?.song ?: run {
                            requireNotNull(track) { "曲目清单信息不可用" }
                            catalogSync.ensureChart(track)
                        }
                        Log.i("PerformanceTiming", "Chart ready ${loaded.id}: ${SystemClock.elapsedRealtime() - t0}ms")
                        loaded
                    }

                    val audioJob = async(Dispatchers.IO) {
                        val t0 = SystemClock.elapsedRealtime()
                        val opusSpec: RecordingFile? = track?.opus?.let { RecordingFile(it.bytes, it.sha256) }
                            ?: currentItem.entry?.song?.recording?.opus
                        if (opusSpec != null) {
                            val existing = track?.let { store.verifiedOpusDirectory(it.opus) }
                                ?: currentItem.entry?.song?.let { store.verifiedDownloadDirectory(it) }
                            if (existing != null) {
                                Log.i("PerformanceTiming", "Audio cache hit $songId: ${SystemClock.elapsedRealtime() - t0}ms")
                                return@async existing
                            }
                            val base = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/performance-audio/excerpt"
                            check(base.startsWith("https://")) { "音乐下载服务尚未配置" }
                            val part = store.partialForOpus(opusSpec)
                            val job = currentCoroutineContext()
                            var lastUiMs = 0L
                            AudioHttpTransfer.download(
                                PerformanceHttp.client,
                                "$base/${opusSpec.sha256}.ogg",
                                part,
                                opusSpec,
                            ) { copied ->
                                job.ensureActive()
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUiMs >= 60L || copied >= opusSpec.bytes) {
                                    lastUiMs = now
                                    val fraction = (copied.toFloat() / opusSpec.bytes).coerceIn(0f, 1f)
                                    val pct = (fraction * 100).toInt()
                                    mutable.update { state ->
                                        state.copy(
                                            downloads = state.downloads + (
                                                songId to AudioDownloadState("正在准备 $pct%", fraction)
                                            ),
                                        )
                                    }
                                }
                            }
                            job.ensureActive()
                            val dir = store.installVerifiedOpus(opusSpec, part)
                            Log.i("PerformanceTiming", "Audio downloaded $songId (${opusSpec.bytes}B): ${SystemClock.elapsedRealtime() - t0}ms")
                            dir
                        } else {
                            null
                        }
                    }

                    val resolvedSong = chartJob.await()
                    val resolvedDir = audioJob.await() ?: withContext(Dispatchers.IO) {
                        store.verifiedDownloadDirectory(resolvedSong)
                    }
                    resolvedSong to resolvedDir
                }

                if (audioDir != null) {
                    val readyEntry = PerformanceEntry(
                        song = song,
                        audioDirectory = audioDir,
                        downloadReady = true,
                    )
                    updateSingleItem(songId) { item ->
                        item.copy(
                            title = item.title.ifBlank { song.title },
                            artist = item.artist.ifBlank { song.artist },
                            durationUs = if (item.durationUs > 0L) item.durationUs else song.durationUs,
                            placeIds = item.placeIds.ifEmpty { song.placeIds },
                            chartReady = true,
                            audioReady = readyEntry.audioReady,
                            entry = readyEntry,
                        )
                    }
                    mutable.value = mutable.value.copy(downloads = mutable.value.downloads - songId)
                    Log.i("PerformanceTiming", "Song prepared $songId total: ${SystemClock.elapsedRealtime() - startedAt}ms")
                    return@launch
                }

                // Fallback for legacy non-opus entries
                val entry = withContext(Dispatchers.IO) { buildEntry(song) }
                updateSingleItem(songId) { item ->
                    item.copy(chartReady = true, audioReady = entry.audioReady, entry = entry)
                }
                if (entry.audioReady) {
                    mutable.value = mutable.value.copy(downloads = mutable.value.downloads - songId)
                } else {
                    PerformanceAudioDownloadWorker.enqueue(getApplication(), entry.song, mobileAllowed)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("PerformanceLibrary", "On-demand song preparation failed for $songId", error)
                mutable.value = mutable.value.copy(
                    error = "音乐准备暂不可用，请重试",
                    downloads = mutable.value.downloads + (songId to AudioDownloadState("音乐准备未完成，请重试", failed = true)),
                )
            } finally {
                preparingSongs.remove(songId)
            }
        }
    }

    private fun updateSingleItem(songId: String, transform: (PerformanceCatalogItem) -> PerformanceCatalogItem) {
        val nextItems = mutable.value.items.map { if (it.id == songId) transform(it) else it }
        mutable.value = mutable.value.copy(
            items = nextItems,
            entries = nextItems.mapNotNull { it.entry },
        )
    }

    fun allowMobile() {
        mobileAllowed = true
        val pendingSong = mutable.value.mobileConfirmationSongId
        mutable.value = mutable.value.copy(mobileConfirmationSongId = null)
        if (pendingSong != null) {
            prepareSong(pendingSong, retry = true)
        }
    }

    fun waitForWifi() {
        mutable.value = mutable.value.copy(mobileConfirmationSongId = null)
    }

    fun importAudio(songId: String, uri: Uri) {
        if (mutable.value.importing != null) return
        val song = mutable.value.entries.find { it.song.id == songId }?.song ?: return
        mutable.value = mutable.value.copy(importing = songId, progress = 0f, notice = null, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        store.import(song, input) { progress ->
                            job.ensureActive()
                            mutable.value = mutable.value.copy(progress = progress)
                        }
                    } ?: error("无法打开音源包，请重新选择")
                }
                val refreshed = loadFromLocalCache()
                publishItems(refreshed, loaded = true, notice = "音源已就绪", importing = null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("PerformanceLibrary", "Unable to import the music", error)
                mutable.value = mutable.value.copy(importing = null, error = "这份音乐无法导入，请重新选择文件")
            }
        }
    }

    fun dismissNotice() {
        mutable.value = mutable.value.copy(notice = null, error = null)
    }

    companion object {
        private const val SYNC_COOLDOWN_MS = 60_000L
    }
}
