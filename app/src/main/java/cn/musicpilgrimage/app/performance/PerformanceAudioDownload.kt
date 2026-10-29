package cn.musicpilgrimage.app.performance

import android.content.Context
import androidx.work.*
import cn.musicpilgrimage.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AudioDownloadState(val label:String,val progress:Float?=null,val failed:Boolean=false)

/** WorkManager owns the queue, so leaving a wall or losing the process doesn't lose a download. */
class PerformanceAudioDownloadWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val id=inputData.getString("song")?:return@withContext Result.failure()
        val version=inputData.getInt("version",-1)
        val song=PerformanceCatalog.load(applicationContext).find{it.id==id&&it.version==version}?:return@withContext Result.failure()
        val store=PerformanceAudioStore(applicationContext)
        val expected=song.recording?.files?.entries?.singleOrNull()?:return@withContext Result.failure()
        try {
            if(store.verifiedDirectory(song)!=null)return@withContext Result.success()
            val base=BuildConfig.SUPABASE_URL.trimEnd('/')+"/storage/v1/object/public/performance-audio/excerpt"
            check(base.startsWith("https://")){"音乐下载服务尚未配置"}
            val part=store.partial(song)
            val partialBefore=part.length()
            val job=currentCoroutineContext()
            var lastProgress=0L
            AudioHttpTransfer.download(client,"$base/${expected.value.sha256}.pcmz",part,expected.value) {copied->
                job.ensureActive()
                val now=System.currentTimeMillis()
                if(now-lastProgress>350L){lastProgress=now;setProgressAsync(workDataOf("fraction" to (copied.toFloat()/expected.value.bytes)))}
            }
            job.ensureActive();store.installDownloaded(song,part)
            android.util.Log.i("PerformanceAudioDownload","Ready ${song.id}; ${expected.value.bytes} verified bytes; partial-before=$partialBefore")
            Result.success(workDataOf("preparedSong" to song.id,"verifiedBytes" to expected.value.bytes,"partialBefore" to partialBefore))
        }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(error:AudioSourceUnavailable){Result.failure(workDataOf("message" to error.message))}
        catch(error:Exception){
            if(runAttemptCount<8)Result.retry()else Result.failure(workDataOf("message" to "音乐准备中断，请重试"))
        }
    }
    companion object {
        internal const val TAG="performance-audio-download"
        private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).build()
        internal suspend fun enqueue(context:Context,song:SongPackage,mobileAllowed:Boolean)=withContext(Dispatchers.IO) {
            val manager=WorkManager.getInstance(context)
            val name="audio:${song.id}:${song.version}"
            val current=manager.getWorkInfosForUniqueWork(name).get().firstOrNull{!it.state.isFinished}
            if(current!=null&&!mobileAllowed)return@withContext
            val builder=OneTimeWorkRequestBuilder<PerformanceAudioDownloadWorker>()
                .setInputData(workDataOf("song" to song.id,"version" to song.version))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if(mobileAllowed)NetworkType.CONNECTED else NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS)
                .addTag(TAG).addTag("audio-song:${song.id}").addTag("audio-version:${song.version}")
            if(current!=null) {
                // Relaxing a Wi-Fi constraint doesn't cancel a transfer or race its partial file.
                manager.updateWork(builder.setId(current.id).build()).get()
            }else manager.enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,builder.build())
        }
    }
}

internal class AudioSourceUnavailable(message:String):IOException(message)

/** Bounded transfer with resumable partial files. A part never becomes a playable file. */
internal object AudioHttpTransfer {
    fun valid(file:File,spec:RecordingFile):Boolean = file.isFile&&file.length()==spec.bytes&&
        file.inputStream().buffered().use{stream->
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
            while(true){val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
            digest.digest().joinToString(""){"%02x".format(it)}==spec.sha256
        }
    fun download(client:OkHttpClient,url:String,part:File,spec:RecordingFile,onProgress:(Long)->Unit={}) {
        check(part.parentFile!!.mkdirs()||part.parentFile!!.isDirectory)
        if(part.length()>spec.bytes)part.delete()
        if(valid(part,spec)){onProgress(spec.bytes);return}
        // A complete but damaged file cannot be resumed from EOF.
        if(part.length()==spec.bytes)part.delete()
        val offset=part.length()
        val request=Request.Builder().url(url).header("Accept-Encoding","identity")
            .apply{if(offset>0)header("Range","bytes=$offset-")}.build()
        client.newCall(request).execute().use{response->
            if(response.code in listOf(400,404))throw AudioSourceUnavailable("音乐下载源尚未就绪")
            if(response.code in listOf(401,403))throw AudioSourceUnavailable("音乐下载服务暂不可用")
            if(!response.isSuccessful)throw IOException("HTTP ${response.code}")
            val resumed=response.code==206
            if(resumed) {
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?:throw IOException("Invalid audio range")
                require(match.groupValues[1].toLong()==offset&&match.groupValues[3].toLong()==spec.bytes){"Audio range does not match"}
            }
            val start=if(resumed)offset else 0L
            RandomAccessFile(part,"rw").use{output->
                if(!resumed)output.setLength(0)
                output.seek(start);var copied=start
                checkNotNull(response.body).byteStream().use{input->
                    val buffer=ByteArray(64*1024)
                    while(true){
                        val count=input.read(buffer);if(count<0)break
                        require(copied+count<=spec.bytes){"Audio exceeds trusted size"}
                        output.write(buffer,0,count);copied+=count;onProgress(copied)
                    }
                }
                output.fd.sync()
            }
        }
        if(!valid(part,spec)) {
            // Retain interrupted prefixes, discard complete corrupt responses.
            if(part.length()>=spec.bytes)part.delete()
            throw IOException("音乐文件校验未通过，正在重新准备")
        }
    }
}
