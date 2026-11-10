package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.performance.LibraryDatabase
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real trip renderer and dialogs using isolated local guest plans. */
class MusicTripUiTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val name="行程界面验证-${System.currentTimeMillis()}"
    private val store get()=MusicTripStore(context.getSharedPreferences("music-space-trips",0))
    private var owner:String?=null

    @After fun removeOnlyTestPlans() {
        owner?.let{key->store.update(key){trips->trips.filterNot{it.name==name||it.name=="$name-已结束"}}}
    }

    private fun capture(name:String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300) // Wait for the WebView and dialog surfaces to reach the compositor.
        val folder=File(context.getExternalFilesDir(null),"trip-planning-review").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->
            File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    private fun findWeb(view:View):WebView? {
        if(view is WebView&&view.url?.endsWith("swiss-details/index.html")==true)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findWeb(view.getChildAt(i))?.let{return it}
        return null
    }

    private fun evaluate(script:String):String {
        val done=CountDownLatch(1)
        var result:String?=null
        compose.runOnUiThread {
            val web=findWeb(compose.activity.window.decorView)
            if(web==null)done.countDown()
            else web.evaluateJavascript(script){result=it;done.countDown()}
        }
        check(done.await(5,TimeUnit.SECONDS)){"Trip WebView did not respond"}
        return result.orEmpty()
    }

    private fun openDay(day:Int) {
        val selector="[data-action=trip-edit-day][data-day='$day']"
        compose.waitUntil(8_000){evaluate("!!document.querySelector(\"$selector\")")=="true"}
        val point=JSONArray(evaluate("""
            (()=>{const button=document.querySelector("$selector");
            button.scrollIntoView({block:'center'});
            const rect=button.getBoundingClientRect(),scale=devicePixelRatio;
            return [(rect.left+rect.width/2)*scale,(rect.top+rect.height/2)*scale];})()
        """.trimIndent()))
        compose.onNodeWithTag("swiss-web-detail").performTouchInput {
            click(Offset(point.getDouble(0).toFloat(),point.getDouble(1).toFloat()))
        }
        compose.waitUntil(5_000){compose.onAllNodesWithTag("trip-day-search").fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun swipingRevealsDeleteAndOnlyConfirmationRemovesTheTrip() {
        val services=(context.applicationContext as PilgrimageApplication).services
        assumeTrue("Do not alter a signed-in account",services.userId.value==null)
        val key=LibraryDatabase.guestOwner(context)
        owner=key
        val today=LocalDate.now()
        val city=MusicTripCity("约克","英国")
        val trip=MusicTrip(UUID.randomUUID().toString(),name,today,today.plusDays(1),listOf(city))
            .add(Place("fixture-place","车站",city.name,city.country,53.9,-1.1),1)
        val other=MusicTrip(UUID.randomUUID().toString(),"$name-已结束",today.minusDays(2),today.minusDays(1),listOf(city))
        services.library.trips.update(key){it+listOf(trip,other)}
        val vm=ViewModelProvider(compose.activity)[MusicTripViewModel::class.java]
        compose.waitUntil(8_000){!vm.state.value.busy&&vm.state.value.owner==key&&vm.state.value.trips.any{it.id==trip.id}}
        var opened:String?=null
        compose.runOnUiThread {compose.activity.setContent {
            val state by vm.state.collectAsState()
            PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                RoomTripList(state,key,listOf(city),vm){opened=it}
            }}
        }}

        val row=compose.onNodeWithTag("trip-row-${trip.id}")
        val delete=compose.onNodeWithTag("trip-delete-${trip.id}")
        fun reveal() {
            row.performScrollTo().performTouchInput{swipeLeft()}
            delete.assertIsDisplayed()
        }
        delete.assertDoesNotExist()
        reveal()
        assertNull(opened)
        delete.performClick()
        compose.onNodeWithText("删除行程？").assertIsDisplayed()
        compose.onNodeWithText("「$name」及每天的全部安排都会被删除。此操作无法撤销。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertTrue(store.read(key).any{it.id==trip.id&&it.stops.size==1})
        reveal()
        delete.performClick()
        compose.onNodeWithText("删除行程").performClick()
        compose.waitUntil(8_000){vm.state.value.trips.none{it.id==trip.id}}
        assertFalse(store.read(key).any{it.id==trip.id})
        assertTrue(store.read(key).any{it.id==other.id})
    }

    @Test fun searchDestinationsAddAcrossCitiesAndEditDirectlyFromDayCard() {
        val services=(context.applicationContext as PilgrimageApplication).services
        assumeTrue("Do not alter a signed-in account",services.userId.value==null)
        val key=LibraryDatabase.guestOwner(context)
        owner=key
        val today=LocalDate.now()
        val expired=MusicTrip(UUID.randomUUID().toString(),"$name-已结束",today.minusDays(4),today.minusDays(1),listOf(MusicTripCity("约克","英国")))
        store.update(key){it+expired}

        val catalog=services.catalog.value.publicOnly()
        val place=catalog.places.first{it.id=="samaritaine"}
        val destinations=listOf(MusicTripCity("约克","英国"),MusicTripCity(place.city,place.country))
        val vm=ViewModelProvider(compose.activity)[MusicTripViewModel::class.java]
        compose.waitUntil(5_000){!vm.state.value.busy&&vm.state.value.owner==key}
        fun current()=vm.state.value.trips.first{it.name==name}
        var openPlaceDialog:(()->Unit)?=null

        compose.runOnUiThread {compose.activity.setContent {
            val state by vm.state.collectAsState()
            var tripId by remember{mutableStateOf<String?>(null)}
            var adding by remember{mutableStateOf(false)}
            openPlaceDialog={adding=true}
            PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                val trip=state.trips.find{it.id==tripId}
                if(trip==null)RoomTripList(state,key,destinations,vm){tripId=it}
                else RoomTripDetailWeb(trip,state,key,vm,{},catalog=catalog)
                if(adding)RoomAddToTripDialog(place,state,key,destinations,vm){adding=false}
            }}
        }}

        compose.onNodeWithText("新建行程").performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("trip-name-input").performTextInput(name)
        val search=compose.onNodeWithTag("trip-destination-search")
        search.performTextInput("英国")
        search.performImeAction()
        capture("destination-search")
        compose.onNodeWithText("英国 · 国家或地区").performClick()
        search.performTextReplacement("未收录测试城")
        compose.onNodeWithText("手动填写目的地").performClick()
        compose.onNodeWithText("城市名称").performTextInput("未收录测试城")
        compose.onNodeWithText("国家或地区名称").performTextInput("英国")
        compose.onNodeWithText("加入目的地").performScrollTo().performClick()
        compose.onNodeWithTag("trip-create-save").performClick()
        compose.waitUntil(8_000){vm.state.value.trips.any{it.name==name}}
        assertEquals(listOf("英国","未收录测试城"),current().cities.map{it.label})

        compose.runOnIdle{openPlaceDialog!!.invoke()}
        compose.onNodeWithTag("trip-choice-${expired.id}").assertDoesNotExist()
        compose.onNodeWithTag("trip-choice-${current().id}").performClick()
        capture("choose-date")
        compose.onNodeWithTag("trip-date-3").performClick()
        compose.waitUntil(8_000){current().stops.size==1}
        assertEquals(3,current().stops.single().day)
        assertTrue(current().cities.any{it.name==place.city&&it.country==place.country})
        assertEquals("false",evaluate("document.body.innerText.includes('待安排')"))

        openDay(1)
        val daySearch=compose.onNodeWithTag("trip-day-search")
        daySearch.performTextInput(place.name)
        daySearch.performImeAction()
        compose.onNodeWithTag("trip-day-place-${place.id}").performClick()
        compose.waitUntil(8_000){current().stops.any{it.day==1&&it.placeId==place.id}}
        compose.onNodeWithText("手动添加活动").performClick()
        compose.onNodeWithTag("trip-custom-name").performTextInput("火车")
        compose.onNodeWithText("添加活动").performClick()
        compose.waitUntil(8_000){current().stops.any{it.day==1&&it.name=="火车"}}
        capture("day-editor")
        compose.onNodeWithText("完成").performClick()

        openDay(3)
        compose.onNodeWithText("换一天").performClick()
        compose.onNodeWithTag("trip-date-1").assertIsNotEnabled()
        compose.onNodeWithTag("trip-date-2").performClick()
        compose.waitUntil(8_000){current().stops.any{it.day==2&&it.placeId==place.id}}
        assertEquals(listOf(1,1,2),store.read(key).first{it.name==name}.stops.map{it.day})
        compose.onNodeWithText("完成").performClick()
        capture("trip-overview")
    }
}
