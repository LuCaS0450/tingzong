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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PerformanceLibraryUi(
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val entries: List<PerformanceEntry> = emptyList(),
    val importing: String? = null,
    val progress: Float = 0f,
    val notice: String? = null,
    val error: String? = null,
    val catalogError: String? = null,
    val downloads: Map<String, AudioDownloadState> = emptyMap(),
    val mobileConfirmation: Boolean = false,
)

internal class PerformanceLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogStore = PerformanceCatalogStore(application)
    private val catalogSync = PerformanceCatalogSync(catalogStore)
    private val store = PerformanceAudioStore(application)
    private val mutable = MutableStateFlow(PerformanceLibraryUi())
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    private var mobileAllowed = false
    private var entered = false
    private var lastSuccessSyncAtMs = 0L
    private val checkedDownloads = mutableMapOf<java.util.UUID, java.io.File?>()

    private fun matches(work: WorkInfo, song: SongPackage): Boolean {
        val opus = PerformanceAudioDownloadWorker.opusTag(song)
        return "audio-song:${song.id}" in work.tags && "audio-version:${song.version}" in work.tags &&
            work.tags.firstOrNull { it.startsWith("audio-opus:") } == opus
    }

    init {
        viewModelScope.launch {
            val cached = loadFromLocalCache()
            if (cached.isNotEmpty()) {
                mutable.value = mutable.value.copy(loaded = true, entries = cached, catalogError = null)
                if (entered) prepareAll()
            }
            refreshCatalog(force = false)
        }
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosByTagFlow(PerformanceAudioDownloadWorker.TAG).collect { infos ->
                val old = mutable.value
                val entries = withContext(Dispatchers.IO) {
                    old.entries.map { entry ->
                        if (entry.downloadReady) entry else {
                            val completed = infos.filter { matches(it, entry.song) && it.state == WorkInfo.State.SUCCEEDED }
                            val directory = completed.firstNotNullOfOrNull { work ->
                                if (!checkedDownloads.containsKey(work.id)) {
                                    checkedDownloads[work.id] = store.verifiedDownloadDirectory(entry.song)
                                }
                                checkedDownloads[work.id]
                            }
                            if (directory != null) entry.copy(audioDirectory = directory, downloadReady = true) else entry
                        }
                    }
                }
                val states = entries.filterNot { it.downloadReady }.associate { entry ->
                    val matching = infos.filter { matches(it, entry.song) && it.state != WorkInfo.State.CANCELLED }
                    val work = matching.firstOrNull { !it.state.isFinished } ?: matching.lastOrNull()
                    val status = when (work?.state) {
                        WorkInfo.State.RUNNING -> AudioDownloadState(
                            "正在准备 ${(work.progress.getFloat("fraction", 0f) * 100).toInt()}%",
                            work.progress.getFloat("fraction", 0f),
                        )
                        WorkInfo.State.FAILED -> AudioDownloadState("这首歌还没准备好", failed = true)
                        else -> AudioDownloadState("音乐准备中")
                    }
                    entry.song.id to status
                }
                // Never overwrite a newer load/import result with an older observer snapshot.
                if (mutable.value.entries == old.entries) {
                    mutable.value = mutable.value.copy(entries = entries, downloads = states)
                }
            }
        }
    }

    private suspend fun loadFromLocalCache(): List<PerformanceEntry> = withContext(Dispatchers.IO) {
        val songs = PerformanceCatalog.load(getApplication())
        buildEntries(songs)
    }

    private fun buildEntries(songs: List<SongPackage>): List<PerformanceEntry> = songs.map { song ->
        val downloaded = store.verifiedDownloadDirectory(song)
        val legacy = if (downloaded == null && song.recording?.opus != null) store.verifiedLegacyDirectory(song) else null
        PerformanceEntry(
            song = song,
            audioDirectory = downloaded ?: legacy,
            downloadReady = downloaded != null || song.recording?.local != true,
        )
    }

    fun onWallEntered() {
        entered = true
        if (mutable.value.entries.isNotEmpty()) {
            prepareAll()
        }
        val elapsed = SystemClock.elapsedRealtime() - lastSuccessSyncAtMs
        if (mutable.value.entries.isEmpty() || lastSuccessSyncAtMs == 0L || elapsed >= SYNC_COOLDOWN_MS) {
            viewModelScope.launch { refreshCatalog(force = false) }
        }
    }

    fun retryCatalog() {
        viewModelScope.launch {
            if (mutable.value.entries.isEmpty()) {
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
                    val current = if (mutable.value.entries.isEmpty()) loadFromLocalCache() else mutable.value.entries
                    mutable.value = mutable.value.copy(
                        loaded = true,
                        syncing = false,
                        entries = current,
                        catalogError = null,
                    )
                }
                is CatalogSyncOutcome.Updated -> {
                    val updatedEntries = withContext(Dispatchers.IO) {
                        store.pruneUnused(outcome.songs)
                        buildEntries(outcome.songs)
                    }
                    checkedDownloads.clear()
                    mutable.value = mutable.value.copy(
                        loaded = true,
                        syncing = false,
                        entries = updatedEntries,
                        catalogError = null,
                    )
                    if (entered && updatedEntries.isNotEmpty()) {
                        prepareAll()
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("PerformanceCatalog", "Cloud catalog sync skipped or failed", error)
            val fallbackEntries = if (mutable.value.entries.isEmpty()) loadFromLocalCache() else mutable.value.entries
            mutable.value = mutable.value.copy(
                loaded = true,
                syncing = false,
                entries = fallbackEntries,
                catalogError = if (fallbackEntries.isEmpty()) "曲库加载未完成，点此重试" else null,
            )
        }
    }

    fun prepareAll(retry: Boolean = false) {
        entered = true
        if (retry) mutable.value = mutable.value.copy(error = null)
        val missing = mutable.value.entries.filterNot { it.downloadReady }
        if (missing.isEmpty()) return
        val connectivity = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivity.activeNetwork != null && connectivity.isActiveNetworkMetered && !mobileAllowed) {
            mutable.value = mutable.value.copy(mobileConfirmation = true)
        }
        viewModelScope.launch {
            try {
                missing.forEach { PerformanceAudioDownloadWorker.enqueue(getApplication(), it.song, mobileAllowed) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    error = "音乐准备暂不可用，请重试",
                    downloads = mutable.value.downloads + missing.associate {
                        it.song.id to AudioDownloadState("音乐准备暂不可用，点此重试", failed = true)
                    },
                )
            }
        }
    }

    fun allowMobile() {
        mobileAllowed = true
        mutable.value = mutable.value.copy(mobileConfirmation = false)
        prepareAll(retry = true)
    }

    fun waitForWifi() {
        mutable.value = mutable.value.copy(mobileConfirmation = false)
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
                mutable.value = mutable.value.copy(loaded = true, entries = refreshed, notice = "音源已就绪", importing = null)
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
