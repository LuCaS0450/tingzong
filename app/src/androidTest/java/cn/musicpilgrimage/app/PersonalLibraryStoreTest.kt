package cn.musicpilgrimage.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Isolated preference files only: no real session, network call or account mutation. */
class PersonalLibraryStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs=context.getSharedPreferences("library-store-probe-${UUID.randomUUID()}",Context.MODE_PRIVATE)
    @After fun cleanup(){prefs.edit().clear().commit()}
    @Test fun acknowledgedUploadCannotEraseALaterRemovalAndItsIntentSurvivesReopen() {
        val owner="user:fixture-a";val other="user:fixture-b"
        val store=MusicFavoriteStore(prefs){42L}
        store.add(owner,"song",false);store.add(other,"other-song",false)
        val sent=store.snapshot(owner)
        store.remove(owner,"song",false)
        store.reconcile(owner,LibraryCloudDocument(1,sent.data,true,sent.clientId),sent)
        val reopened=MusicFavoriteStore(prefs)
        assertTrue(reopened.read(owner).songs.isEmpty());assertTrue(reopened.snapshot(owner).pending)
        reopened.reconcile(owner,LibraryCloudDocument(1,sent.data))
        assertTrue(reopened.read(owner).songs.isEmpty())
        val removal=reopened.snapshot(owner)
        reopened.reconcile(owner,LibraryCloudDocument(2,removal.data,true,removal.clientId),removal)
        assertFalse(MusicFavoriteStore(prefs).snapshot(owner).pending)
        assertEquals(listOf("other-song"),reopened.read(other).songs)
    }
    @Test fun remoteFavoriteAdditionAndLocalUndoKeepTheOriginalDateAndPosition() {
        val owner="user:fixture";var clock=10L;val store=MusicFavoriteStore(prefs){clock++}
        store.add(owner,"older",false);store.add(owner,"newer",false)
        val baseline=store.snapshot(owner)
        store.reconcile(owner,LibraryCloudDocument(1,baseline.data,true,baseline.clientId),baseline)
        val removed=store.remove(owner,"newer",false)!!
        val remote=JSONObject(baseline.data.toString()).apply{put("songs",JSONArray().put(JSONObject().put("id","remote").put("added_at",100L)).apply{baseline.data.getJSONArray("songs").objects().forEach{put(it)}})}
        store.reconcile(owner,LibraryCloudDocument(2,remote))
        assertTrue(store.undo(owner,removed))
        assertEquals(listOf("remote","newer","older"),store.read(owner).songs)
        assertEquals(11L,store.read(owner).time("newer",false))
        assertTrue(store.snapshot(owner).pending)
    }
    @Test fun tripChangesDuringUploadPersistAsOnePlanAndAccountCachesStaySeparate() {
        val owner="user:fixture";val other="user:another"
        val original=MusicTrip(UUID.randomUUID().toString(),"最初安排",LocalDate.of(2026,11,1),LocalDate.of(2026,11,3),listOf(MusicTripCity("巴黎","法国")))
        val events=mutableListOf<Boolean>();val store=MusicTripStore(prefs){_,local->events+=local}
        store.write(owner,listOf(original));store.write(other,listOf(original.copy(id=UUID.randomUUID().toString(),name="另一账号")))
        val sent=store.snapshot(owner)
        store.update(owner){rows->rows.map{it.copy(name="发送途中继续编辑")}}
        store.reconcile(owner,LibraryCloudDocument(1,sent.data,true,sent.clientId),sent)
        val reopened=MusicTripStore(prefs)
        assertEquals(listOf("发送途中继续编辑"),reopened.read(owner).map{it.name})
        assertTrue(reopened.snapshot(owner).pending);assertEquals(listOf("另一账号"),reopened.read(other).map{it.name})
        assertEquals(listOf(true,true,true,false),events)
        val latest=reopened.snapshot(owner)
        reopened.reconcile(owner,LibraryCloudDocument(2,latest.data,true,latest.clientId),latest)
        assertFalse(MusicTripStore(prefs).snapshot(owner).pending)
    }
    @Test fun malformedTripCacheStopsSynchronizationWithoutReplacingTheOriginal() {
        val raw="[{\"id\":\"damaged-record\"}]";prefs.edit().putString("user:fixture:trips",raw).commit()
        val store=MusicTripStore(prefs)
        assertTrue(store.read("user:fixture").isEmpty())
        assertThrows(Exception::class.java){store.snapshot("user:fixture")}
        assertEquals(raw,prefs.getString("user:fixture:trips",null))
    }
}
