package cn.musicpilgrimage.app

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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

/** Real retained SDK session and production store/worker/RPC. Only marked new entries may be removed. */
class NativeLibraryLiveTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val services get()=(context.applicationContext as PilgrimageApplication).services
    private val probe get()=context.getSharedPreferences("native-library-live-probe",Context.MODE_PRIVATE)
    private val result get()=File(context.filesDir,"native-library-live-result.json")
    private fun online():Boolean {
        val network=context.getSystemService(ConnectivityManager::class.java)
        return network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    }
    private suspend fun owner():String {
        withTimeout(30000){services.authReady.first{it}}
        val id=withTimeout(30000){services.userId.first{it!=null}}!!
        val prior=probe.getString("owner",null)
        assertTrue("Unrelated signed-in account is left untouched",prior==null||prior==id)
        return "user:$id"
    }
    private suspend fun settled(owner:String) {
        withTimeout(80000) {
            while(true) {
                assertTrue("Account must stay unchanged",services.userId.value==owner.removePrefix("user:"))
                val state=services.library.state.value
                if(state.owner==owner&&state.status==LibrarySyncStatus.ERROR)throw AssertionError("Native library sync reported an error")
                if(state.owner==owner&&state.status==LibrarySyncStatus.SYNCED&&!services.library.favorites.snapshot(owner).pending&&!services.library.trips.snapshot(owner).pending)return@withTimeout
                delay(200)
            }
        }
    }
    private suspend fun remote(owner:String)=services.call("library_state",expectedOwner=owner.removePrefix("user:")) as JSONObject
    private fun songs(remote:JSONObject,owner:String)=MusicFavoriteCodec.decode(remote.getJSONObject("music_favorites").getJSONObject("data"),owner)
    private fun trips(remote:JSONObject)=MusicTripCodec.decode(remote.getJSONObject("trips").getJSONObject("data"))
    private fun mark(name:String) {
        val value=if(result.isFile)JSONObject(result.readText())else JSONObject()
        result.writeText(value.put(name,true).toString())
    }
    private fun fixture(owner:String,name:String):MusicTrip {
        val place=services.catalog.value.publicOnly().places.first()
        val city=MusicTripCity(place.city,place.country);val start=LocalDate.of(2026,11,1)
        return MusicTrip(UUID.randomUUID().toString(),name,start,start.plusDays(2),listOf(city),
            listOf(MusicTripStop(UUID.randomUUID().toString(),null,"临时同步检查地点",city.name,city.country,3,"检查后移除")))
    }
    private fun capture(name:String) {
        compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(250)
        val folder=File(context.getExternalFilesDir(null),"native-library-live").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun archive() {
        compose.waitUntil(20000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-space-button").performClick()
        compose.onNodeWithTag("room-module-library").performClick()
        compose.onNodeWithTag("room-archive").assertIsDisplayed()
    }
    private fun tripPage(){archive();compose.onNodeWithText("我的行程").performScrollTo().performClick();compose.onNodeWithTag("room-trips").assertIsDisplayed()}
    private suspend fun cleanup(owner:String) {
        if(probe.getString("owner",null)==null)return
        assertTrue("Cleanup only belongs to the active account",probe.getString("owner",null)==owner.removePrefix("user:"))
        val song=probe.getString("song",null);val release=probe.getString("release",null);val trip=probe.getString("trip",null)
        song?.let{services.library.favorites.remove(owner,it,false)}
        release?.let{services.library.favorites.remove(owner,it,true)}
        trip?.let{id->services.library.trips.update(owner){rows->rows.filterNot{it.id==id}}}
        services.library.retry();settled(owner)
        val cloud=remote(owner);val favorites=songs(cloud,owner)
        assertTrue("Temporary song removed from cloud",song==null||song !in favorites.songs)
        assertTrue("Temporary album removed from cloud",release==null||release !in favorites.releases)
        assertTrue("Temporary trip removed from cloud",trip==null||trips(cloud).none{it.id==trip})
        assertTrue(probe.edit().clear().commit())
    }

    @Test fun onlineFavoriteUndoAndTripEdits()=runBlocking {
        val owner=owner();assertTrue("Online phase requires network",online());settled(owner)
        assertTrue("Finish earlier fixture cleanup before starting",probe.all.isEmpty())
        result.writeText(JSONObject().put("retained_real_sdk_session",true).toString())
        val favorites=services.library.favorites;val plans=services.library.trips
        val beforeFavorites=MusicFavoriteCodec.encode(favorites.read(owner));val beforeTrips=MusicTripCodec.encode(plans.read(owner))
        val catalog=MusicCatalogue.from(services.catalog.value)
        val song=catalog.works.firstOrNull{it.id !in favorites.read(owner).songs}?.id ?: error("No unselected song is available; no favorites changed")
        val release=catalog.releases.firstOrNull{it.id !in favorites.read(owner).releases}?.id ?: error("No unselected album is available; no favorites changed")
        val plan=fixture(owner,"在线同步临时检查")
        assertTrue(probe.edit().putString("owner",owner.removePrefix("user:")).putString("song",song).putString("release",release).putString("trip",plan.id).commit())
        try {
            favorites.add(owner,song,false);favorites.add(owner,release,true);plans.update(owner){it+plan}
            settled(owner)
            val added=remote(owner);val date=favorites.read(owner).time(song,false)
            assertTrue("Song and album reached the real service",song in songs(added,owner).songs&&release in songs(added,owner).releases)
            assertTrue("Trip reached the real service",trips(added).any{it.id==plan.id&&it.stops.single().day==3})
            archive();compose.onNodeWithTag("room-drawer-songs").performScrollTo().performClick()
            compose.onNodeWithTag("archive-card-$song").assertIsDisplayed()
            compose.onNodeWithTag("archive-remove-$song").performClick()
            compose.onNodeWithTag("archive-undo-button").performClick()
            settled(owner)
            assertTrue("Undo preserved the original timestamp",songs(remote(owner),owner).time(song,false)==date)
            compose.onNodeWithText("行程和音乐收藏已同步").assertIsDisplayed();capture("online-favorites")
            plans.update(owner){rows->rows.map{if(it.id==plan.id)it.copy(name="在线同步临时检查（已修改）").changeDates(plan.start,plan.start.plusDays(1))else it}}
            settled(owner)
            assertTrue("Shortened dates preserve overflow as unscheduled",trips(remote(owner)).first{it.id==plan.id}.let{it.name.endsWith("（已修改）")&&it.stops.single().day==null})
            mark("online_favorites_trip_edits_and_undo")
        } finally {
            cleanup(owner)
            assertTrue("Existing favorites and dates remain unchanged",libraryJsonEqual(beforeFavorites,MusicFavoriteCodec.encode(favorites.read(owner))))
            assertTrue("Existing plans remain unchanged",libraryJsonEqual(beforeTrips,MusicTripCodec.encode(plans.read(owner))))
        }
        mark("online_fixtures_cleaned_and_existing_data_preserved")
    }

    @Test fun queueEditsWhileOffline()=runBlocking {
        val owner=owner();withTimeout(20000){while(online())delay(200)}
        assertTrue("Earlier fixture cleanup must finish",probe.all.isEmpty())
        val favorites=services.library.favorites
        val song=MusicCatalogue.from(services.catalog.value).works.firstOrNull{it.id !in favorites.read(owner).songs}?.id ?: error("No unselected song is available")
        val plan=fixture(owner,"断网恢复临时检查")
        assertTrue(probe.edit().putString("owner",owner.removePrefix("user:")).putString("song",song).putString("trip",plan.id).putInt("writer-pid",Process.myPid()).commit())
        favorites.add(owner,song,false);services.library.trips.update(owner){it+plan}
        delay(1000)
        assertTrue(favorites.snapshot(owner).pending);assertTrue(services.library.trips.snapshot(owner).pending)
        assertTrue("Offline state must not claim sync success",services.library.state.value.status!=LibrarySyncStatus.SYNCED)
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag("personal-library-sync").get().any{it.state==WorkInfo.State.ENQUEUED})
        tripPage();compose.onNodeWithText("已保存到本机，等待同步").assertIsDisplayed();capture("offline-waiting")
        mark("offline_edits_saved_and_queued")
    }

    @Test fun recoverPendingEditsAfterOfflineProcessRestart()=runBlocking {
        val owner=owner();assertFalse("Remain offline until restart checks finish",online())
        assertNotEquals(probe.getInt("writer-pid",0),Process.myPid())
        val song=probe.getString("song",null)!!;val trip=probe.getString("trip",null)!!
        assertTrue(song in services.library.favorites.read(owner).songs)
        assertTrue(services.library.trips.read(owner).any{it.id==trip})
        assertTrue(services.library.favorites.snapshot(owner).pending);assertTrue(services.library.trips.snapshot(owner).pending)
        tripPage();compose.onNodeWithText("断网恢复临时检查").assertIsDisplayed()
        compose.onNodeWithText("已保存到本机，等待同步").assertIsDisplayed();capture("offline-restored")
        mark("real_sdk_session_and_pending_edits_survived_offline_restart")
    }

    @Test fun reconnectUploadAndCleanup()=runBlocking {
        val owner=owner();withTimeout(30000){while(!online())delay(200)}
        val song=probe.getString("song",null)!!;val trip=probe.getString("trip",null)!!
        try {
            // Connectivity constraints/startup must resume the persisted worker without manual retry.
            settled(owner)
            val cloud=remote(owner)
            assertTrue("Queued song uploaded after reconnect",song in songs(cloud,owner).songs)
            assertTrue("Queued trip uploaded after reconnect",trips(cloud).any{it.id==trip&&it.name=="断网恢复临时检查"})
            tripPage();compose.onNodeWithText("行程和音乐收藏已同步").assertIsDisplayed();capture("reconnected")
            mark("automatic_upload_after_reconnect")
        } finally {cleanup(owner)}
        mark("offline_fixtures_cleaned_from_local_and_cloud")
    }

    @Test fun cleanupPendingFixtures()=runBlocking {cleanup(owner())}
}
