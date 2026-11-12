package cn.musicpilgrimage.app.performance

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Audio is prepared separately; only trusted chart metadata lives in the APK. */
internal class PerformanceAudioStore(context:Context,private val root:File=File(context.filesDir,"performance-audio")) {
    private fun folder(song:SongPackage):File {
        require(Regex("[a-z0-9-]+").matches(song.id) && song.version>0)
        return File(root,"${song.id}/v${song.version}")
    }
    fun directory(song:SongPackage):File? {
        val recording=song.recording?.takeIf{it.local} ?: return null
        val directory=folder(song)
        return directory.takeIf{recording.files.all{(name,spec)->File(it,name).let{f->f.isFile&&f.length()==spec.bytes}}}
    }
    fun verifiedLegacyDirectory(song: SongPackage): File? = synchronized(lock) {
        val recording = song.recording ?: return@synchronized null
        directory(song)?.takeIf { dir ->
            recording.files.all { (name, spec) -> AudioHttpTransfer.valid(File(dir, name), spec) }
        }
    }
    fun verifiedDownloadDirectory(song: SongPackage): File? {
        val (name, spec) = song.recording?.takeIf { it.local }?.downloadFile ?: return null
        return folder(song).takeIf { AudioHttpTransfer.valid(File(it, name), spec) }
    }
    fun partial(song:SongPackage):File {
        val (name, _) = checkNotNull(song.recording?.downloadFile)
        return File(folder(song).apply { mkdirs() }, "$name.part")
    }
    fun installDownloaded(song:SongPackage,part:File):Unit=synchronized(lock) {
        val recording = requireNotNull(song.recording)
        val (name, spec) = checkNotNull(recording.downloadFile)
        require(AudioHttpTransfer.valid(part,spec)){"音乐文件校验未通过"}
        val atomic=AtomicFile(File(folder(song),name))
        val output=atomic.startWrite()
        try {part.inputStream().use{it.copyTo(output)};atomic.finishWrite(output);part.delete()}
        catch(error:Throwable){atomic.failWrite(output);throw error}
        if (recording.opus != null) {
            for (legacyName in recording.files.keys) {
                File(folder(song), legacyName).delete()
                File(folder(song), "$legacyName.part").delete()
            }
        }
    }
    fun import(song:SongPackage,input:InputStream,onProgress:(Float)->Unit={})=synchronized(lock) {
        val recording=requireNotNull(song.recording?.takeIf{it.local}){"这首曲目不需要导入音源"}
        // Local releases deliberately have one original-speed file. No archive paths,
        // extra entries or metadata supplied by the archive can alter the trusted chart.
        val expected=recording.files.entries.single()
        val destination=folder(song).apply{check(mkdirs()||isDirectory){"无法保存歌曲音源"}}
        val atomic=AtomicFile(File(destination,expected.key))
        var output:java.io.FileOutputStream?=null
        try {
            val target=atomic.startWrite();output=target
            ZipInputStream(input).use{zip->
                val entry=zip.nextEntry ?: error("音源包为空")
                require(!entry.isDirectory&&entry.name==expected.key){"这不是当前曲目的音源包"}
                val digest=MessageDigest.getInstance("SHA-256")
                val buffer=ByteArray(64*1024)
                var copied=0L
                while(true) {
                    val count=zip.read(buffer)
                    if(count<0)break
                    copied+=count
                    require(copied<=expected.value.bytes){"音源包大小不匹配"}
                    target.write(buffer,0,count);digest.update(buffer,0,count)
                    onProgress(copied.toFloat()/expected.value.bytes)
                }
                require(copied==expected.value.bytes&&digest.digest().joinToString(""){"%02x".format(it)}==expected.value.sha256){"音源包与曲目不匹配，请重新选择"}
                require(zip.nextEntry==null){"音源包包含多余文件"}
            }
            atomic.finishWrite(output);output=null
        } finally {
            output?.let{atomic.failWrite(it)}
        }
    }
    companion object {private val lock=Any()}
}
