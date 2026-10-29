package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class PersonalLibrarySyncTest {
    private val favorites=PersonalLibraryKind.FAVORITES
    private val trips=PersonalLibraryKind.TRIPS
    private fun fav(vararg ids:String)=JSONObject().put("songs",JSONArray().apply{ids.forEachIndexed{i,id->put(JSONObject().put("id",id).put("added_at",100L-i))}}).put("releases",JSONArray())
    private fun ids(doc:JSONObject)=doc.getJSONArray("songs").objects().map{it.getString("id")}
    private fun trip(id:String="11111111-1111-4111-8111-111111111111",name:String="巴黎旅行")=JSONObject().put("id",id).put("name",name).put("start","2026-11-01").put("end","2026-11-03")
        .put("cities",JSONArray().put(JSONObject().put("name","巴黎").put("country","法国"))).put("stops",JSONArray())
    private fun plans(vararg rows:JSONObject)=JSONObject().put("trips",JSONArray().apply{rows.forEach{put(JSONObject(it.toString()))}})
    private fun merged(kind:PersonalLibraryKind,base:JSONObject,local:JSONObject,remote:JSONObject,dirty:Map<String,Long> = libraryChangedKeys(kind,base,local).associateWith{1L})=PersonalLibraryMerge.merge(kind,base,local,remote,dirty)

    @Test fun legacyImportMergesKindsWithoutReplacingServerDates() {
        val remote=fav("existing").put("releases",JSONArray().put(JSONObject().put("id","album").put("added_at",500L)))
        val local=fav("old","existing");local.getJSONArray("songs").getJSONObject(1).put("added_at",0L)
        val result=merged(favorites,emptyLibraryDocument(favorites),local,remote,libraryChangedKeys(favorites,emptyLibraryDocument(favorites),local).associateWith{0L})
        assertEquals(setOf("old","existing"),ids(result.data).toSet())
        assertEquals(100L,result.data.getJSONArray("songs").objects().first{it.getString("id")=="existing"}.getLong("added_at"))
        assertEquals("album",result.data.getJSONArray("releases").getJSONObject(0).getString("id"))
    }
    @Test fun pendingRemovalAndUndoRemainExplicitEvenWhenTheyReturnToTheBase() {
        val empty=fav();val added=fav("song")
        assertTrue(ids(merged(favorites,empty,empty,added,mapOf("songs:song" to 2L)).data).isEmpty())
        val restored=merged(favorites,added,added,empty,mapOf("songs:song" to 3L)).data
        assertEquals(listOf("song"),ids(restored));assertEquals(100L,restored.getJSONArray("songs").getJSONObject(0).getLong("added_at"))
    }
    @Test fun cleanDeviceDoesNotResurrectACloudRemoval() {
        assertTrue(ids(merged(favorites,fav("song"),fav("song"),fav(),emptyMap()).data).isEmpty())
    }
    @Test fun independentTripChangesMergeWithoutCopies() {
        val a=trip();val b=trip(UUID.randomUUID().toString(),"另一旅行")
        val result=merged(trips,plans(a,b),plans(JSONObject(a.toString()).put("name","本机改名"),b),plans(a,JSONObject(b.toString()).put("name","另一设备改名")))
        assertEquals(0,result.copies);assertEquals(setOf("本机改名","另一设备改名"),result.data.getJSONArray("trips").objects().map{it.getString("name")}.toSet())
    }
    @Test fun concurrentTripEditsPreserveBothVersionsWithAStableCopyId() {
        val base=plans(trip());val local=plans(trip(name="本机安排"));val remote=plans(trip(name="云端安排"))
        val first=merged(trips,base,local,remote);val second=merged(trips,base,local,remote)
        assertEquals(1,first.copies);assertTrue(libraryJsonEqual(first.data,second.data))
        val rows=first.data.getJSONArray("trips").objects()
        assertEquals(setOf("云端安排","本机安排（同步副本）"),rows.map{it.getString("name")}.toSet())
        assertEquals(2,rows.map{it.getString("id")}.distinct().size)
        assertFalse(first.dirty.containsKey(trip().getString("id")))
        assertEquals(1,first.dirty.size)
    }
    @Test fun remotelyDeletedTripKeepsUnsentEditsUnderANewId() {
        val original=trip();val result=merged(trips,plans(original),plans(trip(name="未同步安排")),plans())
        val copy=result.data.getJSONArray("trips").getJSONObject(0)
        assertNotEquals(original.getString("id"),copy.getString("id"));assertEquals("未同步安排（同步副本）",copy.getString("name"))
    }
    @Test fun explicitlyRevertedTripDoesNotSilentlyLoseToAnotherDevice() {
        val base=plans(trip());val result=merged(trips,base,base,plans(trip(name="另一设备安排")),mapOf(trip().getString("id") to 4L))
        assertEquals(2,result.data.getJSONArray("trips").length());assertEquals(1,result.copies)
    }

    private class Store(override val kind:PersonalLibraryKind,initial:JSONObject=emptyLibraryDocument(kind)):LibraryDocumentStore {
        var revision=0L;var base=emptyLibraryDocument(kind);var data=JSONObject(initial.toString());var dirty=mutableMapOf<String,Long>();var generation=0L;val client=UUID.randomUUID().toString()
        override fun snapshot(owner:String)=LibraryLocalDocument(revision,JSONObject(base.toString()),JSONObject(data.toString()),libraryChangedKeys(kind,base,data).associateWith{0L}+dirty,client)
        fun edit(next:JSONObject){generation++;libraryChangedKeys(kind,data,next).forEach{dirty[it]=generation};data=JSONObject(next.toString())}
        override fun reconcile(owner:String,remote:LibraryCloudDocument,acknowledged:LibraryLocalDocument?):LibraryMerge {
            val result=reconcileLibrary(kind,snapshot(owner),remote,acknowledged)
            revision=remote.revision;base=JSONObject(remote.data.toString());data=JSONObject(result.data.toString());dirty=result.dirty.toMutableMap()
            return result
        }
    }
    private class Cloud:PersonalLibraryCloud {
        val documents=PersonalLibraryKind.entries.associateWith{LibraryCloudDocument(0,emptyLibraryDocument(it))}.toMutableMap()
        var beforeRead:(()->Unit)?=null;var beforeWrite:(()->Unit)?=null;var afterApply:(()->Unit)?=null;var writes=0
        override suspend fun read(owner:String):Map<PersonalLibraryKind,LibraryCloudDocument>{beforeRead?.invoke();return documents.mapValues{it.value.copy(data=JSONObject(it.value.data.toString()),applied=false)}}
        override suspend fun write(owner:String,kind:PersonalLibraryKind,expected:Long,data:JSONObject,clientId:String):LibraryCloudDocument {
            writes++;beforeWrite?.also{beforeWrite=null}?.invoke()
            val row=documents.getValue(kind)
            if(row.revision!=expected)return row.copy(applied=false)
            val next=LibraryCloudDocument(row.revision+1,JSONObject(data.toString()),true,clientId);documents[kind]=next
            afterApply?.also{afterApply=null}?.invoke();return next
        }
    }
    @Test fun cancelingFavoriteDuringItsUploadCannotBeResurrectedByTheAck()=runBlocking {
        val local=Store(favorites);local.edit(fav("song"));val cloud=Cloud()
        cloud.afterApply={local.edit(fav())}
        val result=PersonalLibraryEngine(listOf(local),cloud).synchronize("owner"){true}
        assertFalse(result.pending);assertTrue(ids(local.data).isEmpty());assertTrue(ids(cloud.documents.getValue(favorites).data).isEmpty());assertEquals(2,cloud.writes)
    }
    @Test fun lostSuccessfulResponseAndLaterRemovalSurviveTheNextPull()=runBlocking {
        val local=Store(favorites);local.edit(fav("song"));val cloud=Cloud()
        cloud.afterApply={local.edit(fav());throw IOException("simulated response loss")}
        val engine=PersonalLibraryEngine(listOf(local),cloud)
        try{engine.synchronize("owner"){true};fail("Expected response loss")}catch(_:IOException){}
        assertTrue(local.snapshot("user:owner").pending)
        engine.synchronize("owner"){true}
        assertTrue(ids(local.data).isEmpty());assertTrue(ids(cloud.documents.getValue(favorites).data).isEmpty())
    }
    @Test fun editingTripDuringItsOwnUploadDoesNotCreateAConflictCopy()=runBlocking {
        val local=Store(trips);local.edit(plans(trip(name="第一版")));val cloud=Cloud()
        cloud.afterApply={local.edit(plans(trip(name="第二版")))}
        val result=PersonalLibraryEngine(listOf(local),cloud).synchronize("owner"){true}
        assertEquals(0,result.copies);assertEquals(1,local.data.getJSONArray("trips").length());assertEquals("第二版",local.data.getJSONArray("trips").getJSONObject(0).getString("name"));assertEquals(2,cloud.writes)
    }
    @Test fun lostTripReplyThenAnotherLocalEditDoesNotInventASecondDeviceConflict()=runBlocking {
        val local=Store(trips);local.edit(plans(trip(name="已发送安排")));val cloud=Cloud()
        cloud.afterApply={local.edit(plans(trip(name="后来继续编辑")));throw IOException("simulated lost reply")}
        val engine=PersonalLibraryEngine(listOf(local),cloud)
        try{engine.synchronize("owner"){true};fail("Expected response loss")}catch(_:IOException){}
        val result=engine.synchronize("owner"){true}
        assertEquals(0,result.copies);assertEquals(1,local.data.getJSONArray("trips").length())
        assertEquals("后来继续编辑",cloud.documents.getValue(trips).data.getJSONArray("trips").getJSONObject(0).getString("name"))
    }
    @Test fun compareAndSwapConflictRebasesPendingChangesBeforeRetry()=runBlocking {
        val local=Store(favorites);local.edit(fav("local"));val cloud=Cloud()
        cloud.beforeWrite={cloud.documents[favorites]=LibraryCloudDocument(1,fav("remote"))}
        PersonalLibraryEngine(listOf(local),cloud).synchronize("owner"){true}
        assertEquals(setOf("local","remote"),ids(local.data).toSet());assertEquals(2,cloud.writes);assertFalse(local.snapshot("user:owner").pending)
    }
    @Test fun accountChangeAfterReadCannotInstallOrPublishItsResponse()=runBlocking {
        val local=Store(favorites);local.edit(fav("private"));val cloud=Cloud();var current=true
        cloud.beforeRead={current=false}
        try{PersonalLibraryEngine(listOf(local),cloud).synchronize("owner"){current};fail("Expected owner guard")}catch(_:LibraryOwnerChanged){}
        assertEquals(0,cloud.writes);assertEquals(0L,local.revision);assertEquals(listOf("private"),ids(local.data))
    }
    @Test fun accountChangeAfterWriteLeavesItsPendingCacheUnacknowledged()=runBlocking {
        val local=Store(favorites);local.edit(fav("private"));val cloud=Cloud();var current=true
        cloud.afterApply={current=false}
        try{PersonalLibraryEngine(listOf(local),cloud).synchronize("owner"){current};fail("Expected owner guard")}catch(_:LibraryOwnerChanged){}
        assertTrue(local.snapshot("user:owner").pending);assertEquals(0L,local.revision)
    }
    @Test fun failedReadKeepsBothTheLocalContentAndPendingIntention()=runBlocking {
        val local=Store(favorites);local.edit(fav("offline"));val cloud=Cloud();cloud.beforeRead={throw IOException("offline")}
        try{PersonalLibraryEngine(listOf(local),cloud).synchronize("owner"){true};fail("Expected offline failure")}catch(_:IOException){}
        assertEquals(listOf("offline"),ids(local.data));assertTrue(local.snapshot("user:owner").pending);assertEquals(0,cloud.writes)
    }
    @Test fun malformedAcknowledgementCannotClearPendingChanges() {
        val local=Store(favorites);local.edit(fav("private"));val sent=local.snapshot("user:owner")
        assertThrows(IllegalArgumentException::class.java){local.reconcile("user:owner",LibraryCloudDocument(1,fav(),true),sent)}
        assertTrue(local.snapshot("user:owner").pending);assertEquals(listOf("private"),ids(local.data))
    }
}
