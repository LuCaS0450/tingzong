package cn.musicpilgrimage.app.performance

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Audio is prepared separately and cached by content hash so chart revisions reuse audio. */
internal class PerformanceAudioStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "performance-audio"))
    constructor(@Suppress("UNUSED_PARAMETER") context: Context, root: File) : this(root)

    private val blobsDir = File(root, "blobs")

    private fun legacyFolder(song: SongPackage): File {
        require(Regex("[a-z0-9-]+").matches(song.id) && song.version > 0)
        return File(root, "${song.id}/v${song.version}")
    }

    internal fun opusBlob(spec: RecordingFile): File {
        require(CloudCatalogManifest.SHA256_REGEX.matches(spec.sha256)) { "无效的音频校验值" }
        return File(blobsDir, "${spec.sha256}.ogg")
    }

    fun directory(song: SongPackage): File? {
        val recording = song.recording?.takeIf { it.local } ?: return null
        val directory = legacyFolder(song)
        return directory.takeIf {
            recording.files.all { (name, spec) ->
                File(it, name).let { f -> f.isFile && f.length() == spec.bytes }
            }
        }
    }

    fun verifiedLegacyDirectory(song: SongPackage): File? = synchronized(lock) {
        val recording = song.recording ?: return@synchronized null
        directory(song)?.takeIf { dir ->
            recording.files.all { (name, spec) -> AudioHttpTransfer.valid(File(dir, name), spec) }
        }
    }

    fun verifiedOpusDirectory(opus: CloudOpusRef): File? = synchronized(lock) {
        val spec = RecordingFile(opus.bytes, opus.sha256)
        if (isVerifiedOpusBlob(opusBlob(spec), spec)) blobsDir else null
    }

    fun verifiedDownloadDirectory(song: SongPackage): File? = synchronized(lock) {
        val recording = song.recording?.takeIf { it.local } ?: return@synchronized null
        val opus = recording.opus
        if (opus != null) {
            if (isVerifiedOpusBlob(opusBlob(opus), opus)) return@synchronized blobsDir
            val legacyDir = legacyFolder(song)
            if (AudioHttpTransfer.valid(File(legacyDir, RecordedMusic.OPUS_FILENAME), opus)) {
                return@synchronized legacyDir
            }
            return@synchronized null
        }
        val (name, spec) = recording.downloadFile ?: return@synchronized null
        return@synchronized legacyFolder(song).takeIf { AudioHttpTransfer.valid(File(it, name), spec) }
    }

    fun partialForOpus(spec: RecordingFile): File =
        File(blobsDir.apply { mkdirs() }, "${spec.sha256}.ogg.part")

    fun partial(song: SongPackage): File {
        val recording = checkNotNull(song.recording?.takeIf { it.local })
        val opus = recording.opus
        if (opus != null) {
            return partialForOpus(opus)
        }
        val (name, _) = checkNotNull(recording.downloadFile)
        return File(legacyFolder(song).apply { mkdirs() }, "$name.part")
    }

    /** Installs a part file already verified by AudioHttpTransfer.download into blobs/<sha256>.ogg via atomic rename. */
    fun installVerifiedOpus(spec: RecordingFile, part: File): File = synchronized(lock) {
        require(part.isFile && part.length() == spec.bytes) { "音乐文件校验未通过" }
        val target = opusBlob(spec)
        moveOrCopyAtomic(part, target)
        markVerifiedBlob(target, spec)
        blobsDir
    }

    fun installDownloaded(song: SongPackage, part: File): Unit = synchronized(lock) {
        val recording = requireNotNull(song.recording)
        val (name, spec) = checkNotNull(recording.downloadFile)
        require(AudioHttpTransfer.valid(part, spec)) { "音乐文件校验未通过" }
        val target = if (recording.opus != null) {
            opusBlob(recording.opus)
        } else {
            File(legacyFolder(song), name)
        }
        moveOrCopyAtomic(part, target)
        if (recording.opus != null) {
            markVerifiedBlob(target, recording.opus)
            val legacyDir = legacyFolder(song)
            for (legacyName in recording.files.keys) {
                File(legacyDir, legacyName).delete()
                File(legacyDir, "$legacyName.part").delete()
            }
            File(legacyDir, RecordedMusic.OPUS_FILENAME).delete()
            File(legacyDir, "${RecordedMusic.OPUS_FILENAME}.part").delete()
        }
    }

    fun pruneUnused(activeSongs: List<SongPackage>) = synchronized(lock) {
        val activeOpusNames = activeSongs.mapNotNull { it.recording?.opus?.sha256 }.mapTo(mutableSetOf()) { "$it.ogg" }
        val activeSongIds = activeSongs.mapTo(mutableSetOf()) { it.id }
        pruneByActiveSets(activeOpusNames, activeSongIds)
    }

    fun pruneUnusedManifest(activeTracks: List<CloudCatalogTrack>) = synchronized(lock) {
        val activeOpusNames = activeTracks.mapTo(mutableSetOf()) { "${it.opus.sha256}.ogg" }
        val activeSongIds = activeTracks.mapTo(mutableSetOf()) { it.id }
        pruneByActiveSets(activeOpusNames, activeSongIds)
    }

    private fun pruneByActiveSets(activeOpusNames: Set<String>, activeSongIds: Set<String>) {
        blobsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".ogg") && file.name !in activeOpusNames) {
                file.delete()
            }
        }
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != "blobs" && dir.name !in activeSongIds) {
                dir.deleteRecursively()
            }
        }
    }

    fun import(song: SongPackage, input: InputStream, onProgress: (Float) -> Unit = {}) = synchronized(lock) {
        val recording = requireNotNull(song.recording?.takeIf { it.local }) { "这首曲目不需要导入音源" }
        // Local releases deliberately have one original-speed file. No archive paths,
        // extra entries or metadata supplied by the archive can alter the trusted chart.
        val expected = recording.files.entries.single()
        val destination = legacyFolder(song).apply { check(mkdirs() || isDirectory) { "无法保存歌曲音源" } }
        val temp = File.createTempFile(".import-", ".part", destination)
        try {
            FileOutputStream(temp).use { target ->
                ZipInputStream(input).use { zip ->
                    val entry = zip.nextEntry ?: error("音源包为空")
                    require(!entry.isDirectory && entry.name == expected.key) { "这不是当前曲目的音源包" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= expected.value.bytes) { "音源包大小不匹配" }
                        target.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(copied.toFloat() / expected.value.bytes)
                    }
                    require(
                        copied == expected.value.bytes &&
                            digest.digest().joinToString("") { "%02x".format(it) } == expected.value.sha256
                    ) { "音源包与曲目不匹配，请重新选择" }
                    require(zip.nextEntry == null) { "音源包包含多余文件" }
                }
                target.fd.sync()
            }
            moveAtomic(temp, File(destination, expected.key))
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    companion object {
        private val lock = Any()
        private val verifiedBlobs = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

        internal fun isVerifiedOpusBlob(file: File, spec: RecordingFile): Boolean {
            if (!file.isFile || file.length() != spec.bytes) return false
            val key = "${file.absolutePath}|${spec.sha256}"
            val cached = verifiedBlobs[key]
            val mod = file.lastModified()
            if (cached != null && cached.first == spec.bytes && cached.second == mod) return true
            if (!AudioHttpTransfer.valid(file, spec)) return false
            verifiedBlobs[key] = spec.bytes to mod
            return true
        }

        private fun markVerifiedBlob(file: File, spec: RecordingFile) {
            verifiedBlobs["${file.absolutePath}|${spec.sha256}"] = spec.bytes to file.lastModified()
        }

        private fun moveOrCopyAtomic(source: File, target: File) {
            val parent = requireNotNull(target.parentFile)
            check(parent.mkdirs() || parent.isDirectory) { "无法保存歌曲音源" }
            if (source.parentFile?.canonicalPath == parent.canonicalPath) {
                try {
                    moveAtomic(source, target)
                    return
                } catch (_: Exception) {
                    // Fall back to copy+rename if cross-device or move fails.
                }
            }
            atomicCopyAndDelete(source, target)
        }

        private fun atomicCopyAndDelete(source: File, target: File) {
            val parent = requireNotNull(target.parentFile)
            check(parent.mkdirs() || parent.isDirectory) { "无法保存歌曲音源" }
            val temp = File.createTempFile(".install-", ".part", parent)
            try {
                FileOutputStream(temp).use { out ->
                    source.inputStream().use { it.copyTo(out) }
                    out.fd.sync()
                }
                moveAtomic(temp, target)
                source.delete()
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
        }

        private fun moveAtomic(temp: File, target: File) {
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
        }
    }
}
