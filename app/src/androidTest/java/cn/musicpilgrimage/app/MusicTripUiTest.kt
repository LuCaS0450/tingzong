package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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

/** Isolated local guest plans exercise native controls; no authenticated state is injected. */
class MusicTripUiTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="行程界面验证-${System.currentTimeMillis()}"
    private var owner:String?=null
    @After fun removeOnlyTheTestPlan() {
        owner?.let{key->val store=MusicTripStore(context.getSharedPreferences("music-space-trips",0));store.write(key,store.read(key).filterNot{it.name==name})}
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(350)
        val folder=File(context.getExternalFilesDir(null),"music-space-stage6").apply{mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun createAddMoveConfirmRemovalAndPersistNativePlan() {
        compose.waitUntil(12000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        val services=(context.applicationContext as PilgrimageApplication).services
        assumeTrue("Do not alter a signed-in account",services.userId.value==null)
        val key=LibraryDatabase.guestOwner(context);owner=key
        val place=services.catalog.value.publicOnly().places.first{it.id=="samaritaine"}
        val vm=ViewModelProvider(compose.activity)[MusicTripViewModel::class.java]
        compose.waitUntil(5000){!vm.state.value.busy&&vm.state.value.owner==key}
        var open:(()->Unit)?=null
        compose.runOnUiThread {compose.activity.setContent {
            val state by vm.state.collectAsState()
            var tripId by remember{mutableStateOf<String?>(null)}
            var adding by remember{mutableStateOf(false)}
            open={adding=true}
            PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                Column {
                    RoomHeader(if(tripId==null)"我的行程"else "旅行安排",if(tripId!=null)({tripId=null})else null,onSpace={})
                    Box(Modifier.weight(1f)){
                        val trip=state.trips.find{it.id==tripId}
                        if(trip==null)RoomTripList(state,key,listOf(MusicTripCity("巴黎","法国")),vm){tripId=it}
                        else RoomTripDetail(trip,state,key,vm,{})
                    }
                }
                if(adding)RoomAddToTripDialog(place,state,key,listOf(MusicTripCity("巴黎","法国")),vm,{adding=false}){tripId=it}
            }}
        }}
        compose.onNodeWithText("新建行程").performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("trip-name-input").performTextInput(name)
        compose.onNodeWithText("巴黎 · 法国").performClick()
        compose.onNodeWithText("创建行程").performScrollTo().performClick()
        compose.waitUntil(8000){vm.state.value.trips.any{it.name==name}}
        compose.onNodeWithTag("room-trip-detail").assertIsDisplayed()
        compose.runOnIdle{open!!.invoke()}
        compose.onNodeWithTag("trip-choice-${vm.state.value.trips.first{it.name==name}.id}").performClick()
        compose.onNodeWithTag("trip-add-confirm").performClick()
        compose.waitUntil(8000){vm.state.value.trips.first{it.name==name}.stops.size==1}
        val id=vm.state.value.trips.first{it.name==name}.stops.single().id
        compose.onNodeWithTag("trip-stop-$id").performScrollTo().assertIsDisplayed();capture("daily-plan")
        compose.onNodeWithContentDescription("地点安排操作").performClick()
        compose.onNodeWithText("移到另一天").performClick()
        compose.onNodeWithTag("trip-day-input").performTextClearance();compose.onNodeWithTag("trip-day-input").performTextInput("2")
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(8000){vm.state.value.trips.first{it.name==name}.stops.single().day==2}
        val store=MusicTripStore(context.getSharedPreferences("music-space-trips",0))
        assertEquals(2,store.read(key).first{it.name==name}.stops.single().day)
        compose.onNodeWithTag("trip-stop-$id").performScrollTo()
        compose.onNodeWithContentDescription("地点安排操作").performClick()
        compose.onNodeWithText("从行程移出").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1,vm.state.value.trips.first{it.name==name}.stops.size)
        compose.onNodeWithContentDescription("地点安排操作").performClick()
        compose.onNodeWithText("从行程移出").performClick()
        compose.onNodeWithText("移出").performClick()
        compose.waitUntil(8000){vm.state.value.trips.first{it.name==name}.stops.isEmpty()}
        compose.onNodeWithTag("room-back").performClick();capture("trips")
    }
}
