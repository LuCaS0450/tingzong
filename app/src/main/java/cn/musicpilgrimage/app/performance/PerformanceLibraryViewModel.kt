package cn.musicpilgrimage.app.performance

import android.app.Application
import android.net.Uri
import android.net.ConnectivityManager
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PerformanceLibraryUi(val loaded:Boolean=false,val entries:List<PerformanceEntry> = emptyList(),
    val importing:String?=null,val progress:Float=0f,val notice:String?=null,val error:String?=null,
    val downloads:Map<String,AudioDownloadState> = emptyMap(),val mobileConfirmation:Boolean=false)

internal class PerformanceLibraryViewModel(application:Application):AndroidViewModel(application) {
    private val store=PerformanceAudioStore(application)
    private val mutable=MutableStateFlow(PerformanceLibraryUi())
    val state=mutable.asStateFlow()
    private var mobileAllowed=false
    private var entered=false
    private val checkedDownloads=mutableMapOf<java.util.UUID,java.io.File?>()
    init {
        viewModelScope.launch{load()}
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosByTagFlow(PerformanceAudioDownloadWorker.TAG).collect{infos->
                val old=mutable.value
                val entries=withContext(Dispatchers.IO){old.entries.map{entry->
                    if(entry.audioReady)entry else {
                        val completed=infos.filter{"audio-song:${entry.song.id}" in it.tags&&"audio-version:${entry.song.version}" in it.tags&&it.state==WorkInfo.State.SUCCEEDED}
                        val directory=completed.firstNotNullOfOrNull{work->
                            if(!checkedDownloads.containsKey(work.id))checkedDownloads[work.id]=store.verifiedDirectory(entry.song)
                            checkedDownloads[work.id]
                        }
                        if(directory!=null)entry.copy(audioDirectory=directory)else entry
                    }
                }}
                val states=entries.filterNot{it.audioReady}.associate{entry->
                    val matching=infos.filter{"audio-song:${entry.song.id}" in it.tags&&"audio-version:${entry.song.version}" in it.tags&&it.state!=WorkInfo.State.CANCELLED}
                    val work=matching.firstOrNull{!it.state.isFinished}?:matching.lastOrNull()
                    val status=when(work?.state) {
                        WorkInfo.State.RUNNING->AudioDownloadState("正在准备 ${(work.progress.getFloat("fraction",0f)*100).toInt()}%",work.progress.getFloat("fraction",0f))
                        WorkInfo.State.FAILED->AudioDownloadState(work.outputData.getString("message")?:"准备失败，点此重试",failed=true)
                        WorkInfo.State.ENQUEUED,WorkInfo.State.BLOCKED->AudioDownloadState(if(work.runAttemptCount>0)"等待网络，连接后自动继续"else "等待后台准备")
                        else->AudioDownloadState("等待自动准备")
                    }
                    entry.song.id to status
                }
                // Never overwrite a newer load/import result with an older observer snapshot.
                if(mutable.value.entries==old.entries)mutable.value=mutable.value.copy(entries=entries,downloads=states)
            }
        }
    }
    private suspend fun load() {
        val songs=withContext(Dispatchers.IO){PerformanceCatalog.load(getApplication())}
        val entries=withContext(Dispatchers.IO){songs.map{PerformanceEntry(it,audioDirectory=store.verifiedDirectory(it))}}
        mutable.value=mutable.value.copy(loaded=true,entries=entries)
        if(entered)prepareAll()
    }
    fun prepareAll(retry:Boolean=false) {
        entered=true
        if(retry)mutable.value=mutable.value.copy(error=null)
        val missing=mutable.value.entries.filterNot{it.audioReady}
        if(missing.isEmpty())return
        val connectivity=getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if(connectivity.activeNetwork!=null&&connectivity.isActiveNetworkMetered&&!mobileAllowed)mutable.value=mutable.value.copy(mobileConfirmation=true)
        viewModelScope.launch {
            try {missing.forEach{PerformanceAudioDownloadWorker.enqueue(getApplication(),it.song,mobileAllowed)}}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){mutable.value=mutable.value.copy(error="音乐准备暂不可用，请重试",
                downloads=mutable.value.downloads+missing.associate{it.song.id to AudioDownloadState("音乐准备暂不可用，点此重试",failed=true)})}
        }
    }
    fun allowMobile(){mobileAllowed=true;mutable.value=mutable.value.copy(mobileConfirmation=false);prepareAll(retry=true)}
    fun waitForWifi(){mutable.value=mutable.value.copy(mobileConfirmation=false)}
    fun importAudio(songId:String,uri:Uri) {
        if(mutable.value.importing!=null)return
        val song=mutable.value.entries.find{it.song.id==songId}?.song ?: return
        mutable.value=mutable.value.copy(importing=songId,progress=0f,notice=null,error=null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job=currentCoroutineContext()
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {input->
                        store.import(song,input){progress->job.ensureActive();mutable.value=mutable.value.copy(progress=progress)}
                    } ?: error("无法打开音源包，请重新选择")
                }
                load()
                mutable.value=mutable.value.copy(notice="音源已就绪",importing=null)
            } catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){mutable.value=mutable.value.copy(importing=null,error=error.message ?: "音源导入失败，请重试")}
        }
    }
    fun dismissNotice(){mutable.value=mutable.value.copy(notice=null,error=null)}
}
