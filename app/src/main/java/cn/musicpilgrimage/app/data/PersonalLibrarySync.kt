package cn.musicpilgrimage.app.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.*
import cn.musicpilgrimage.app.PilgrimageApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class LibrarySyncStatus { LOCAL, WAITING, SYNCING, SYNCED, ERROR }
internal data class PersonalLibrarySyncState(val owner:String="",val status:LibrarySyncStatus=LibrarySyncStatus.LOCAL,val notice:String?=null,val error:String?=null)

/** One durable network-constrained worker per signed-in account. Guest caches never enter it. */
internal class PersonalLibrarySync(private val context:Context,private val services:AppServices) {
    private val mutableChanges=MutableStateFlow(0L)
    val changes=mutableChanges.asStateFlow()
    private val mutableState=MutableStateFlow(PersonalLibrarySyncState())
    val state=mutableState.asStateFlow()
    private val mutex=Mutex()
    private val deleting=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val manager by lazy{WorkManager.getInstance(context)}
    val favorites=MusicFavoriteStore(context.getSharedPreferences("music-space-favorites",Context.MODE_PRIVATE),::changed)
    val trips=MusicTripStore(context.getSharedPreferences("music-space-trips",Context.MODE_PRIVATE),::changed)
    private val cloud=object:PersonalLibraryCloud {
        private fun document(row:JSONObject)=LibraryCloudDocument(row.getLong("revision"),row.getJSONObject("data"),row.optBoolean("applied"),row.stringOrNull("client_id"))
        override suspend fun read(owner:String):Map<PersonalLibraryKind,LibraryCloudDocument> {
            val response=services.call("library_state",JSONObject(),owner) as JSONObject
            return PersonalLibraryKind.entries.associateWith{document(response.getJSONObject(it.wire))}
        }
        override suspend fun write(owner:String,kind:PersonalLibraryKind,expected:Long,data:JSONObject,clientId:String)=document(
            services.call("library_sync",JSONObject().put("kind",kind.wire).put("expected_revision",expected).put("data",data).put("client_id",clientId),owner) as JSONObject)
    }
    private val engine=PersonalLibraryEngine(listOf(favorites,trips),cloud)
    init {
        services.scope.launch {
            services.authReady.first{it}
            var previous:String?=null
            services.userId.collect{id->
                if(previous!=null&&previous!=id)manager.cancelUniqueWork(workName(previous!!))
                previous=id
                mutableState.value=PersonalLibrarySyncState(id?.let{"user:$it"}.orEmpty(),if(id==null)LibrarySyncStatus.LOCAL else LibrarySyncStatus.WAITING)
                if(id!=null)request()
            }
        }
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:Activity){if(services.authReady.value)request()}
            override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?){}
            override fun onActivityStarted(activity:Activity){}
            override fun onActivityPaused(activity:Activity){}
            override fun onActivityStopped(activity:Activity){}
            override fun onActivitySaveInstanceState(activity:Activity,outState:Bundle){}
            override fun onActivityDestroyed(activity:Activity){}
        })
    }
    private fun changed(owner:String,local:Boolean) {
        mutableChanges.update{it+1}
        if(local)request(owner,replace=true,delay=true)
    }
    private fun request(expected:String?=null,replace:Boolean=false,delay:Boolean=false) {
        services.scope.launch {
            val id=services.userId.value?:return@launch
            if(!services.authReady.value||id in deleting||expected!=null&&expected!="user:$id")return@launch
            mutableState.update{PersonalLibrarySyncState("user:$id",LibrarySyncStatus.WAITING,it.takeIf{row->row.owner=="user:$id"}?.notice)}
            val task=OneTimeWorkRequestBuilder<PersonalLibraryWorker>()
                .setInputData(workDataOf("owner" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                .addTag("personal-library-sync")
                .apply{if(delay)setInitialDelay(700,TimeUnit.MILLISECONDS)}.build()
            manager.enqueueUniqueWork(workName(id),if(replace)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,task)
        }
    }
    fun retry(){request(replace=true)}
    suspend fun synchronize(id:String):LibrarySyncResult=mutex.withLock {
        fun current()=services.authReady.value&&services.userId.value==id&&id !in deleting
        if(!current())throw LibraryOwnerChanged()
        mutableState.update{PersonalLibrarySyncState("user:$id",LibrarySyncStatus.SYNCING,it.notice)}
        try {
            val result=withContext(Dispatchers.IO){engine.synchronize(id,::current)}
            if(current())mutableState.update{PersonalLibrarySyncState("user:$id",if(result.pending)LibrarySyncStatus.WAITING else LibrarySyncStatus.SYNCED,
                if(result.copies>0)"另一设备也修改了行程，两个版本已分别保留。"else it.notice)}
            result
        } catch(e:CancellationException) {
            if(current())mutableState.update{it.copy(status=LibrarySyncStatus.WAITING,error=null)}
            throw e
        } catch(e:Exception) {
            if(current())mutableState.update{it.copy(status=LibrarySyncStatus.ERROR,error=when {
                e is ApiException&&e.code==401->"登录已过期，请重新登录后同步。本机更改已保留。"
                e is ApiException&&e.code in 400..499->e.message
                e is IOException->"暂时无法同步，本机更改已保留。"
                else->"同步尚未完成，本机更改已保留。"
            })}
            throw e
        }
    }
    fun localFailure(message:String){mutableState.update{it.copy(owner=services.userId.value?.let{id->"user:$id"}?:cn.musicpilgrimage.app.performance.LibraryDatabase.guestOwner(context),status=LibrarySyncStatus.ERROR,error=message)}}
    fun clearAccount(id:String) {
        deleting+=id;manager.cancelUniqueWork(workName(id))
        favorites.clear("user:$id");trips.clear("user:$id")
    }
    private fun workName(id:String)="personal-library:$id"
}

class PersonalLibraryWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val id=inputData.getString("owner")?:return Result.failure()
        val services=(applicationContext as PilgrimageApplication).services
        services.authReady.first{it}
        if(services.userId.value!=id)return Result.success()
        return try {if(services.library.synchronize(id).pending)Result.retry()else Result.success()}
        catch(e:CancellationException){throw e}
        catch(e:LibraryOwnerChanged){Result.success()}
        catch(e:ApiException){if(e.code in 400..499&&e.code!=429)Result.failure()else Result.retry()}
        catch(_:Exception){Result.retry()}
    }
}
