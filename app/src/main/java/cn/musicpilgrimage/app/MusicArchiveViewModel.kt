package cn.musicpilgrimage.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class MusicArchiveState(
    val owner: String? = null, val places: List<String> = emptyList(),
    val visits: List<ContentItem> = emptyList(), val loading: Boolean = false, val error: String? = null
)
data class PlaceFavoriteUndo(val token: Long, val owner: String, val id: String, val index: Int)

/** Reuses existing account favorites and certified visits; never creates local certification. */
class MusicArchiveViewModel(app: Application) : AndroidViewModel(app) {
    private val services = (app as PilgrimageApplication).services
    private val mutable = MutableStateFlow(MusicArchiveState())
    val state = mutable.asStateFlow()
    private val undoMutable = MutableStateFlow<PlaceFavoriteUndo?>(null)
    val undo = undoMutable.asStateFlow()
    private val lock = Mutex()
    private var sequence = 0L
    private data class Change(val owner: String, val id: String, val enabled: Boolean, val version: Long, val index:Int=0)
    private val pending = mutableMapOf<String, Change>()
    private val changes = Channel<Change>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            combine(services.userId, services.changed) { id, _ -> id }.collectLatest { owner ->
                if (mutable.value.owner != owner) {
                    pending.clear();undoMutable.value=null
                    mutable.value=MusicArchiveState(owner=owner,loading=owner!=null)
                }
                if (owner != null) refresh(owner)
            }
        }
        viewModelScope.launch {
            for (change in changes) {
              var failure:String?=null
              lock.withLock {
                if (services.userId.value != change.owner) return@withLock
                try {
                    services.repositories.content.save("favorite", JSONObject().put("location_id",change.id).put("enabled",change.enabled),change.owner)
                    if (pending[change.id]?.version == change.version) pending.remove(change.id)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (services.userId.value == change.owner && pending[change.id]?.version == change.version) {
                        pending.remove(change.id);undoMutable.value=null
                        val current=mutable.value
                        val rollback=current.places.toMutableList()
                        if(change.enabled)rollback.remove(change.id) else if(change.id !in rollback)rollback.add(change.index.coerceAtMost(rollback.size),change.id)
                        failure=FeatureViewModel.friendlyError(e)
                        mutable.value=current.copy(places=rollback,error=failure)
                    }
                }
              }
              // A timed-out mutation may have reached the server. Reconcile before accepting the next queued command.
              if(failure!=null&&services.userId.value==change.owner) {
                  refresh(change.owner)
                  if(services.userId.value==change.owner)mutable.value=mutable.value.copy(error=failure)
              }
            }
        }
    }

    private suspend fun refresh(owner: String) = lock.withLock {
        try {
            val profile=services.repositories.auth.self()
            val array=profile.optJSONArray("favorites")
            val ids=if(array==null)emptyList() else List(array.length()){array.getString(it)}.distinct()
            val visits=mutableListOf<ContentItem>()
            // The existing endpoint returns 30 rows per page. Do not silently truncate older visits.
            while(true) {
                val page=services.repositories.content.list(kind="visit",mine=true,offset=visits.size)
                val unseen=page.filter{row->visits.none{it.id==row.id}}
                visits.addAll(unseen)
                if(page.size<30 || unseen.isEmpty())break
            }
            if(services.userId.value==owner) {
                val desired=ids.toMutableList()
                pending.values.filter{it.owner==owner}.forEach{if(it.enabled){if(it.id !in desired)desired.add(0,it.id)}else desired.remove(it.id)}
                val previous=mutable.value.places
                val ordered=desired.filter{it !in previous}+previous.filter{it in desired}
                mutable.value=MusicArchiveState(owner,ordered,visits.sortedByDescending{it.raw.optString("verified_at").ifBlank{it.raw.optString("created_at")}},false)
            }
        } catch(e:CancellationException){throw e}
        catch(e:Exception){if(services.userId.value==owner)mutable.value=mutable.value.copy(loading=false,error=FeatureViewModel.friendlyError(e))}
    }

    fun reload() { val owner=services.userId.value ?: return;viewModelScope.launch{mutable.value=mutable.value.copy(loading=true,error=null);refresh(owner)} }
    fun removePlace(id:String) {
        val owner=services.userId.value ?: return
        val current=mutable.value.takeIf{it.owner==owner} ?: return
        val index=current.places.indexOf(id);if(index<0)return
        val version=++sequence
        undoMutable.value=PlaceFavoriteUndo(version,owner,id,index)
        mutable.value=current.copy(places=current.places-id,error=null)
        enqueue(Change(owner,id,false,version,index))
    }
    fun togglePlace(id:String) {
        val owner=services.userId.value ?: return
        val current=mutable.value.takeIf{it.owner==owner&&!it.loading} ?: return
        if(id in current.places){removePlace(id);return}
        undoMutable.value=null
        mutable.value=current.copy(places=listOf(id)+current.places,error=null)
        enqueue(Change(owner,id,true,++sequence))
    }
    fun undoRemoval(token:Long) {
        val undo=undoMutable.value?.takeIf{it.token==token&&it.owner==services.userId.value} ?: return
        val ids=mutable.value.places.toMutableList()
        if(undo.id !in ids)ids.add(undo.index.coerceAtMost(ids.size),undo.id)
        mutable.value=mutable.value.copy(places=ids,error=null)
        enqueue(Change(undo.owner,undo.id,true,++sequence))
        undoMutable.value=null
    }
    private fun enqueue(change:Change){pending[change.id]=change;changes.trySend(change)}
    fun dismissUndo(token:Long){if(undoMutable.value?.token==token)undoMutable.value=null}
    fun deleteVisitContent(item:ContentItem) {
        val owner=services.userId.value ?: return
        if(item.owner!=owner)return
        viewModelScope.launch {lock.withLock {
            try {services.repositories.content.save("delete_content",JSONObject().put("id",item.id),owner)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(services.userId.value==owner)mutable.value=mutable.value.copy(error=FeatureViewModel.friendlyError(e))}
        }}
    }
}
