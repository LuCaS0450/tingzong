package cn.musicpilgrimage.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.performance.LibraryDatabase
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Only this test's guest fixture is added and removed. No account or audio data is cleared. */
class MusicTripDragUiTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val id="drag-check-${UUID.randomUUID()}"
    private val models=ViewModelStore()
    private lateinit var vm:MusicTripViewModel
    private var owner:String?=null
    private val a get()="$id-a"
    private val b get()="$id-b"
    private val c get()="$id-c"
    private fun saved()=MusicTripStore(context.getSharedPreferences("music-space-trips",0))
    private fun current()=vm.state.value.trips.first{it.id==id}
    @After fun removeOnlyFixture() {
        compose.mainClock.autoAdvance=true
        models.clear()
        owner?.let{key->saved().write(key,saved().read(key).filterNot{it.id==id})}
    }
    private fun open(days:Int=3,duplicate:Boolean=false) {
        compose.waitUntil(12000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        val services=(context.applicationContext as PilgrimageApplication).services
        assumeTrue("Do not alter a signed-in account",services.userId.value==null)
        val key=LibraryDatabase.guestOwner(context);owner=key
        val date=LocalDate.of(2026,11,1)
        val stops=listOf(
            MusicTripStop(a,"samaritaine","莎玛丽丹百货","巴黎","法国",1),
            MusicTripStop(b,"blue-train","蓝色列车餐厅","巴黎","法国",1),
            MusicTripStop(c,if(duplicate)"samaritaine"else "eiffel","埃菲尔铁塔","巴黎","法国",2))
        val trip=MusicTrip(id,"巴黎音乐之旅",date,date.plusDays(days-1L),listOf(MusicTripCity("巴黎","法国")),stops)
        saved().write(key,saved().read(key)+trip)
        vm=ViewModelProvider(models,ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[MusicTripViewModel::class.java]
        compose.waitUntil(5000){!vm.state.value.busy&&vm.state.value.trips.any{it.id==id}}
        compose.runOnUiThread{compose.activity.setContent {
            val state by vm.state.collectAsState()
            PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column {RoomHeader("旅行安排",onBack={},onSpace={});Box(Modifier.weight(1f)){RoomTripDetail(state.trips.first{it.id==id},state,key,vm,{})}}
            }}
        }}
        compose.onNodeWithTag("room-trip-detail").assertIsDisplayed()
    }
    private fun center(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center-compose.onNodeWithTag("room-trip-detail").fetchSemanticsNode().boundsInRoot.topLeft
    private fun capture(name:String) {
        val folder=File(context.getExternalFilesDir(null),"music-space-stage6").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun drag(stop:String,destination:Offset,cancel:Boolean=false,preview:Boolean=false) {
        val start=center("trip-drag-handle-$stop")
        compose.onNodeWithTag("room-trip-detail").performTouchInput{down(start);advanceEventTime(650);moveTo(start+Offset(0f,2f),delayMillis=16)}
        compose.onNodeWithTag("trip-drag-preview").assertExists()
        compose.onNodeWithTag("room-trip-detail").performTouchInput{moveTo(destination,delayMillis=260)}
        if(preview){compose.onNodeWithTag("trip-drop-hint").assertExists();capture("trip-drag-active")}
        compose.onNodeWithTag("room-trip-detail").performTouchInput{if(cancel)cancel()else up()}
        compose.waitUntil(5000){!vm.state.value.busy&&compose.onAllNodesWithTag("trip-drag-preview").fetchSemanticsNodes().isEmpty()}
    }
    @Test fun reorderCrossDayCancelAndPersistPreserveEveryStop() {
        open()
        val initial=current()
        drag(a,center("trip-stop-$b")-Offset(0f,8f))
        assertEquals(initial,current())
        drag(a,center("trip-stop-$b")+Offset(0f,8f),preview=true)
        assertEquals(listOf(b,a),current().stops.filter{it.day==1}.map{it.id})
        val unchanged=current()
        drag(b,center("trip-day-2"),cancel=true)
        assertEquals(unchanged,current())
        drag(a,center("trip-day-2"))
        assertEquals(listOf(a,c),current().stops.filter{it.day==2}.map{it.id})
        assertEquals(setOf(a,b,c),current().stops.map{it.id}.toSet())
        assertEquals(current(),saved().read(owner!!).first{it.id==id})
        compose.onAllNodesWithContentDescription("拖动地点").assertCountEquals(3)
        compose.waitForIdle()
        capture("trip-drag-plan")
    }
    @Test fun duplicateDropReturnsCardWithoutChangingSavedPlan() {
        open(duplicate=true)
        val before=current()
        drag(a,center("trip-day-2"))
        assertEquals(before,current())
        assertEquals(before,saved().read(owner!!).first{it.id==id})
        compose.onNodeWithText("这个地点已安排在第2天，请选择另一天。").assertExists()
    }
    @Test fun edgeScrollKeepsGestureAfterSourceLeavesViewportAndDropsIntoEmptyDay() {
        open(days=10)
        val start=center("trip-drag-handle-$a")
        val area=compose.onNodeWithTag("room-trip-detail").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance=false
        try {
            compose.onNodeWithTag("room-trip-detail").performTouchInput{down(start)}
            compose.mainClock.advanceTimeBy(650)
            compose.onNodeWithTag("room-trip-detail").performTouchInput{moveTo(Offset(start.x,area.height-12f),delayMillis=80)}
            compose.mainClock.advanceTimeBy(1200)
            compose.onNodeWithTag("trip-drag-preview").assertExists()
            capture("trip-drag-edge")
            compose.onNodeWithTag("room-trip-detail").performTouchInput{up()}
        } finally {compose.mainClock.autoAdvance=true}
        compose.waitUntil(5000){!vm.state.value.busy&&current().stops.first{it.id==a}.day!!>=3}
        compose.waitUntil(5000){compose.onAllNodesWithTag("trip-drag-preview").fetchSemanticsNodes().isEmpty()}
        assertEquals(setOf(a,b,c),current().stops.map{it.id}.toSet())
        assertEquals(current(),saved().read(owner!!).first{it.id==id})
    }
}
