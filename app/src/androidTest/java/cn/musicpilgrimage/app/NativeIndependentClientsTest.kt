package cn.musicpilgrimage.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Run in two independently initialized emulator data directories, using ordinary SDK logins. */
class NativeIndependentClientsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val services get()=(context.applicationContext as PilgrimageApplication).services
    private fun fixture():JSONObject {
        val file=File(context.filesDir,"acceptance-credentials.json");assertTrue(file.isFile)
        val f=JSONObject(file.readText());assertTrue(file.delete());return f
    }
    private fun mark(name:String) {
        val file=File(context.filesDir,"emulator-acceptance-result.json")
        val result=if(file.isFile)JSONObject(file.readText())else JSONObject()
        file.writeText(result.put(name,true).toString())
    }
    private fun id(f:JSONObject)=UUID.nameUUIDFromBytes((f.getString("nonce")+"two-clients-trip").toByteArray()).toString()
    private fun owner(f:JSONObject)="user:"+f.getJSONObject("accounts").getJSONObject("a").getString("id")
    private fun songs()=MusicCatalogue.from(services.catalog.value).works.take(2).map{it.id}
    private suspend fun ready(f:JSONObject,login:Boolean=false) {
        services.authReady.first{it}
        val a=f.getJSONObject("accounts").getJSONObject("a")
        if(login&&services.userId.value!=a.getString("id")) {
            if(services.userId.value!=null)services.signOut()
            services.signIn(a.getString("email"),a.getString("password"))
        }
        withTimeout(25000){services.userId.first{it==a.getString("id")}}
        withTimeout(15000){services.catalog.first{MusicCatalogue.from(it).works.size>=2}}
        assertEquals(a.getString("id"),(services.call("self") as JSONObject).getString("id"))
    }
    private suspend fun settled(f:JSONObject) {
        val owner=owner(f)
        withTimeout(90000){while(true){
            val state=services.library.state.value
            if(state.owner==owner&&state.status==LibrarySyncStatus.ERROR)throw AssertionError("Real independent-client synchronization reported an error")
            if(state.owner==owner&&state.status==LibrarySyncStatus.SYNCED&&!services.library.favorites.snapshot(owner).pending&&!services.library.trips.snapshot(owner).pending)return@withTimeout
            delay(200)
        }}
    }
    private suspend fun pull(f:JSONObject) {
        services.library.retry()
        // Deliver the production Main-scope enqueue before inspecting its state.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){Unit}
        settled(f)
    }
    private fun online():Boolean {
        val network=context.getSystemService(ConnectivityManager::class.java)
        return network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    }
    private fun base(f:JSONObject):MusicTrip {
        val start=LocalDate.of(2026,11,10);val city=MusicTripCity("Paris","France")
        return MusicTrip(id(f),"双端同步验收",start,start.plusDays(1),listOf(city),emptyList())
    }
    @Test fun primaryCreatesOnlineBase()=runBlocking {
        val f=fixture();ready(f,true);settled(f);val owner=owner(f)
        assertTrue("Disposable account must start without the scoped trip",services.library.trips.read(owner).none{it.id==id(f)})
        services.library.favorites.add(owner,songs()[0],false)
        services.library.trips.update(owner){it+base(f)};settled(f)
        context.getSharedPreferences("two-clients",Context.MODE_PRIVATE).edit().putString("origin",services.library.favorites.snapshot(owner).clientId).commit()
        mark("independent_primary_online_base")
    }
    @Test fun primaryQueuesConflictingChangesOffline()=runBlocking {
        val f=fixture();services.authReady.first{it};assertEquals(owner(f).removePrefix("user:"),services.userId.value)
        withTimeout(15000){while(online())delay(200)}
        val owner=owner(f);assertTrue(services.library.trips.read(owner).any{it.id==id(f)})
        services.library.favorites.remove(owner,songs()[0],false)
        services.library.trips.update(owner){rows->rows.map{if(it.id==id(f))it.copy(name="主端离线修改")else it}}
        delay(1000);assertTrue(services.library.favorites.snapshot(owner).pending);assertTrue(services.library.trips.snapshot(owner).pending)
        mark("independent_primary_real_offline_changes")
    }
    @Test fun peerReadsBaseAndWritesConflict()=runBlocking {
        val f=fixture();ready(f,true);settled(f);val owner=owner(f)
        assertEquals("双端同步验收",services.library.trips.read(owner).single{it.id==id(f)}.name)
        assertTrue(songs()[0] in services.library.favorites.read(owner).songs)
        // This peer has its own Android filesystem, SDK session and generated library origin.
        services.library.favorites.add(owner,songs()[1],false)
        services.library.trips.update(owner){rows->rows.map{if(it.id==id(f))it.copy(name="副端在线修改")else it}}
        settled(f);mark("independent_peer_genuine_login_pull_and_conflict_write")
    }
    @Test fun primaryReconnectsAndMergesOnce()=runBlocking {
        val f=fixture();ready(f);withTimeout(15000){while(!online())delay(200)};settled(f);val owner=owner(f)
        val rows=services.library.trips.read(owner)
        assertEquals("副端在线修改",rows.single{it.id==id(f)}.name)
        val copy=rows.single{it.name=="主端离线修改（同步副本）"}
        assertNotEquals(id(f),copy.id)
        assertFalse(songs()[0] in services.library.favorites.read(owner).songs)
        assertTrue(songs()[1] in services.library.favorites.read(owner).songs)
        pull(f)
        assertEquals(1,services.library.trips.read(owner).count{it.id==copy.id})
        assertEquals(2,services.library.trips.read(owner).size)
        mark("independent_primary_deletion_union_and_stable_conflict_copy")
    }
    @Test fun peerObservesMergedStateAndRemovesFixtures()=runBlocking {
        val f=fixture();ready(f);pull(f);val owner=owner(f)
        val rows=services.library.trips.read(owner)
        assertEquals("副端在线修改",rows.single{it.id==id(f)}.name)
        val copy=rows.single{it.name=="主端离线修改（同步副本）"}
        assertEquals(2,rows.size);assertFalse(songs()[0] in services.library.favorites.read(owner).songs)
        assertTrue(songs()[1] in services.library.favorites.read(owner).songs)
        services.library.favorites.remove(owner,songs()[1],false)
        val exactIds=setOf(id(f),copy.id)
        services.library.trips.update(owner){it.filterNot{row->row.id in exactIds}};settled(f)
        mark("independent_peer_observed_merge_and_uploaded_deletion")
    }
    @Test fun primaryObservesRemoteCleanup()=runBlocking {
        val f=fixture();ready(f);pull(f);val owner=owner(f)
        assertTrue(services.library.trips.read(owner).isEmpty());assertTrue(services.library.favorites.read(owner).songs.isEmpty())
        mark("independent_primary_observed_peer_cleanup")
    }
}
