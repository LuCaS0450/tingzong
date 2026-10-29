package cn.musicpilgrimage.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class PersonalLibraryKind(val wire:String) { FAVORITES("music_favorites"), TRIPS("trips") }
internal data class LibraryCloudDocument(val revision:Long,val data:JSONObject,val applied:Boolean=false,val clientId:String?=null) { init {require(revision>=0)} }
internal data class LibraryLocalDocument(val revision:Long,val base:JSONObject,val data:JSONObject,val dirty:Map<String,Long> = emptyMap(),val clientId:String="") {
    val pending get()=dirty.isNotEmpty()||!libraryJsonEqual(base,data)
}
internal data class LibraryMerge(val data:JSONObject,val copies:Int=0,val dirty:Map<String,Long> = emptyMap())
internal interface LibraryDocumentStore {
    val kind:PersonalLibraryKind
    fun snapshot(owner:String):LibraryLocalDocument
    fun reconcile(owner:String,remote:LibraryCloudDocument,acknowledged:LibraryLocalDocument?=null):LibraryMerge
}

/** Canonical comparison also makes a conflict-copy id stable after a lost network response. */
internal fun libraryJson(value:Any?):String=when(value) {
    null,JSONObject.NULL->"null"
    is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+libraryJson(value.get(it))}
    is JSONArray->(0 until value.length()).joinToString(",","[","]"){libraryJson(value.get(it))}
    is String->JSONObject.quote(value)
    else->value.toString()
}
internal fun libraryJsonEqual(a:Any?,b:Any?)=libraryJson(a)==libraryJson(b)
internal fun emptyLibraryDocument(kind:PersonalLibraryKind)=when(kind) {
    PersonalLibraryKind.FAVORITES->JSONObject().put("songs",JSONArray()).put("releases",JSONArray())
    PersonalLibraryKind.TRIPS->JSONObject().put("trips",JSONArray())
}

internal fun libraryChangedKeys(kind:PersonalLibraryKind,before:JSONObject,after:JSONObject):Set<String> {
    fun rows(doc:JSONObject):Map<String,JSONObject> = if(kind==PersonalLibraryKind.TRIPS)doc.getJSONArray("trips").objects().associateBy{it.getString("id")}
        else listOf("songs","releases").flatMap{type->doc.getJSONArray(type).objects().map{"$type:${it.getString("id")}" to it}}.toMap()
    val a=rows(before);val b=rows(after)
    return (a.keys+b.keys).filterNot{libraryJsonEqual(a[it],b[it])}.toSet()
}

internal fun reconcileLibrary(kind:PersonalLibraryKind,local:LibraryLocalDocument,remote:LibraryCloudDocument,acknowledged:LibraryLocalDocument?):LibraryMerge {
    if(acknowledged!=null&&remote.applied) {
        require(libraryJsonEqual(acknowledged.data,remote.data)&&remote.revision==acknowledged.revision+1&&remote.clientId==acknowledged.clientId&&local.clientId==acknowledged.clientId){"同步确认无效，本机数据已保留"}
        // A later edit (including undoing an in-flight addition) keeps its newer generation.
        return LibraryMerge(JSONObject(local.data.toString()),dirty=local.dirty.filter{acknowledged.dirty[it.key]!=it.value})
    }
    // The previous write from this cache may have succeeded after its HTTP reply was
    // lost. Keep this cache's latest intent; the dirty generations still require an ack.
    if(local.clientId.isNotBlank()&&remote.clientId==local.clientId)return LibraryMerge(JSONObject(local.data.toString()),dirty=local.dirty)
    return PersonalLibraryMerge.merge(kind,local.base,local.data,remote.data,local.dirty)
}

internal object PersonalLibraryMerge {
    fun merge(kind:PersonalLibraryKind,base:JSONObject,local:JSONObject,remote:JSONObject,dirty:Map<String,Long> = libraryChangedKeys(kind,base,local).associateWith{0L}):LibraryMerge {
        if(dirty.isEmpty()&&libraryJsonEqual(local,base))return LibraryMerge(JSONObject(remote.toString()))
        if(libraryJsonEqual(remote,base)||libraryJsonEqual(local,remote))return LibraryMerge(JSONObject(local.toString()),dirty=dirty)
        return if(kind==PersonalLibraryKind.FAVORITES)favorites(base,local,remote,dirty)else trips(base,local,remote,dirty)
    }
    private fun favorites(base:JSONObject,local:JSONObject,remote:JSONObject,dirty:Map<String,Long>):LibraryMerge {
        val result=JSONObject()
        for(kind in listOf("songs","releases")) {
            fun entries(doc:JSONObject)=doc.getJSONArray(kind).objects().associate{it.getString("id") to it.getLong("added_at")}
            val b=entries(base);val l=entries(local);val r=entries(remote)
            val order=(l.keys+r.keys).distinct().withIndex().associate{it.value to it.index}
            val merged=(b.keys+l.keys+r.keys).distinct().mapNotNull{id->
                val before=b[id];val left=l[id];val right=r[id]
                val token=dirty["$kind:$id"];val changed=token!=null||left!=before
                // Unacknowledged explicit actions, including undo, remain the local intention.
                // An initial import may inherit the server's known addition date.
                val chosen=when {!changed->right;token==0L&&before==null&&left!=null&&right!=null->maxOf(left,right);else->left}
                chosen?.let{id to it}
            }.sortedWith(compareByDescending<Pair<String,Long>>{it.second}.thenBy{order[it.first]?:Int.MAX_VALUE}.thenBy{it.first})
            result.put(kind,JSONArray().apply{merged.forEach{put(JSONObject().put("id",it.first).put("added_at",it.second))}})
        }
        return LibraryMerge(result,dirty=dirty)
    }
    private fun trips(base:JSONObject,local:JSONObject,remote:JSONObject,dirty:Map<String,Long>):LibraryMerge {
        fun rows(doc:JSONObject)=doc.getJSONArray("trips").objects().associateBy{it.getString("id")}
        val b=rows(base);val l=rows(local);val r=rows(remote)
        val result=linkedMapOf<String,JSONObject>();var copies=0;val pending=dirty.toMutableMap()
        for(id in (r.keys+l.keys+b.keys).distinct()) {
            val before=b[id];val left=l[id];val right=r[id]
            val chosen=when {
                id !in dirty&&libraryJsonEqual(left,before)->right
                libraryJsonEqual(right,before)||libraryJsonEqual(left,right)->left
                else->{
                    // Concurrent edits never replace the other device's itinerary. A deletion
                    // on the server remains a deletion; unsent local edits survive under a new id.
                    if(left!=null) {
                        val copyId=UUID.nameUUIDFromBytes(("trip-conflict:$id:"+libraryJson(left)).toByteArray(Charsets.UTF_8)).toString()
                        if(!l.containsKey(copyId)&&!r.containsKey(copyId)) {
                            result[copyId]=JSONObject(left.toString()).put("id",copyId).put("name",left.getString("name").take(54)+"（同步副本）")
                            copies++
                        }
                        pending[copyId]=dirty[id]?:0L
                    } else copies++ // A remotely edited trip is retained when this device deleted it.
                    pending.remove(id)
                    right
                }
            }
            if(chosen!=null)result[id]=JSONObject(chosen.toString())
        }
        return LibraryMerge(JSONObject().put("trips",JSONArray().apply{result.values.sortedWith(compareBy<JSONObject>{it.getString("start")}.thenBy{it.getString("id")}).forEach(::put)}),copies,pending)
    }
}

internal interface PersonalLibraryCloud {
    suspend fun read(owner:String):Map<PersonalLibraryKind,LibraryCloudDocument>
    /** Both a successful compare-and-swap and a conflict return the current server document. */
    suspend fun write(owner:String,kind:PersonalLibraryKind,expected:Long,data:JSONObject,clientId:String):LibraryCloudDocument
}
internal data class LibrarySyncResult(val pending:Boolean,val copies:Int)
internal class LibraryOwnerChanged:IllegalStateException("账号已切换，同步已停止")

/** Durable local/base snapshots are updated only after an owner-checked server response. */
internal class PersonalLibraryEngine(private val stores:List<LibraryDocumentStore>,private val cloud:PersonalLibraryCloud) {
    suspend fun synchronize(owner:String,isCurrent:()->Boolean):LibrarySyncResult {
        suspend fun guard(){currentCoroutineContext().ensureActive();if(!isCurrent())throw LibraryOwnerChanged()}
        val key="user:$owner";var copies=0
        guard();val remote=cloud.read(owner);guard()
        require(remote.keys.containsAll(stores.map{it.kind})){"同步结果不完整，本机数据已保留"}
        for(store in stores){guard();copies+=store.reconcile(key,remote.getValue(store.kind)).copies}
        repeat(4) {
            for(store in stores) {
                guard();val local=store.snapshot(key)
                if(local.pending) {
                    require(local.clientId.isNotBlank()){"同步缓存标记无效，本机数据已保留"}
                    val response=cloud.write(owner,store.kind,local.revision,JSONObject(local.data.toString()),local.clientId);guard()
                    require(response.revision>=local.revision){"同步版本无效，本机数据已保留"}
                    copies+=store.reconcile(key,response,local).copies
                }
            }
            guard();if(stores.none{it.snapshot(key).pending})return LibrarySyncResult(false,copies)
        }
        return LibrarySyncResult(true,copies)
    }
}
