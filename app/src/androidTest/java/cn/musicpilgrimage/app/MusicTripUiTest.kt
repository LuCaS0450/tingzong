package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import java.time.LocalDate
import java.util.UUID

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
        if (!shouldCaptureReviewScreenshot(name)) return
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300) // Wait for the dialog surfaces to reach the compositor.
        val folder=File(context.getExternalFilesDir(null),"trip-planning-review").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->
            File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    private fun openDay(day:Int) {
        compose.onNodeWithTag("trip-archive-scroll").performScrollToNode(hasTestTag("trip-day-$day"))
        compose.onNodeWithTag("trip-day-$day").performClick()
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
        var opened by mutableStateOf<String?>(null)
        compose.runOnUiThread {compose.activity.setContent {
            val state by vm.state.collectAsState()
            PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                val detail=state.trips.find{it.id==opened}
                if(detail==null)RoomTripList(state,key,listOf(city),vm){opened=it}
                else RoomTripDetail(detail,state,key,vm,{},onBack={opened=null})
            }}
        }}

        val row=compose.onNodeWithTag("trip-row-${trip.id}")
        val delete=compose.onNodeWithTag("trip-delete-${trip.id}")
        fun reveal() {
            row.performScrollTo().performTouchInput{swipeLeft()}
            delete.assertIsDisplayed()
        }
        delete.assertDoesNotExist()
        compose.onNodeWithText("左滑行程可删除").assertDoesNotExist()
        compose.onNodeWithText("同步", substring = false).assertDoesNotExist()
        capture("trip-cards")
        val closedBounds = row.fetchSemanticsNode().boundsInRoot
        reveal()
        val openBounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(openBounds.left < closedBounds.left)
        assertTrue(openBounds.left >= 0f)
        assertTrue(compose.onNodeWithText(trip.displayName).fetchSemanticsNode().boundsInRoot.left >= openBounds.left)
        capture("trip-card-swiped")
        row.performTouchInput { swipeRight() }
        delete.assertDoesNotExist()
        assertEquals(closedBounds.left, row.fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(closedBounds.width, row.fetchSemanticsNode().boundsInRoot.width, 1f)
        reveal()
        assertNull(opened)
        delete.performClick()
        compose.onNodeWithText("删除行程？").assertIsDisplayed()
        compose.onNodeWithText("「$name」及每天的全部安排都会被删除。此操作无法撤销。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertTrue(store.read(key).any{it.id==trip.id&&it.stops.size==1})

        row.performClick()
        compose.onNodeWithTag("trip-archive-settings").performClick()
        compose.onNodeWithText("修改日期").assertIsDisplayed()
        capture("shared-trip-settings")
        compose.onNodeWithText("修改日期").performClick()
        compose.onNodeWithText("修改出行日期").assertIsDisplayed()
        compose.onNodeWithText("名称与目的地").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("trip-archive-scroll")
            .performScrollToNode(hasTestTag("trip-stop-${trip.stops.single().id}"))
        compose.onNodeWithContentDescription("地点安排操作").performClick()
        compose.onNodeWithText("从行程移出").performClick()
        compose.onNodeWithText("从行程移出这个活动？").assertIsDisplayed()
        capture("shared-remove-activity")
        compose.onNodeWithText("取消").performClick()
        assertEquals(1,store.read(key).first{it.id==trip.id}.stops.size)
        compose.runOnIdle{opened=null}

        reveal()
        delete.performClick()
        capture("shared-delete-trip")
        compose.onNodeWithText("删除行程").performClick()
        compose.waitUntil(8_000){vm.state.value.trips.none{it.id==trip.id}}
        assertFalse(store.read(key).any{it.id==trip.id})
        assertTrue(store.read(key).any{it.id==other.id})
    }

    @ExtendedDeviceTest
    @Test fun taiwanAndHongKongCanBeSelectedIndependentlyAsRegions() {
        val services = (context.applicationContext as PilgrimageApplication).services
        val destinations = services.catalog.value.publicOnly().places
            .filter { it.country == "中国台湾" || it.country == "中国香港" }
            .map { MusicTripCity(it.city, it.country) }.distinct()
        var selected by mutableStateOf(emptyList<MusicTripCity>())
        compose.runOnUiThread {
            compose.activity.setContent {
                PilgrimageTheme {
                    TripDestinationFields("旅行", {}, destinations, selected, { selected = it }, false)
                }
            }
        }
        val search = compose.onNodeWithTag("trip-destination-search")
        search.performTextInput("中华民国")
        compose.onNodeWithText("中国台湾 · 国家或地区").performClick()
        search.performTextReplacement("中国香港")
        compose.onNode(hasText("中国香港") and !hasTestTag("trip-destination-search")).performClick()
        compose.runOnIdle { assertEquals(listOf("中国台湾", "中国香港"), selected.map { it.label }) }
        capture("region-selection")
    }

    @ExtendedDeviceTest
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
                else RoomTripDetail(trip,state,key,vm,{},catalog=catalog)
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
        compose.onNodeWithText("待安排",substring=true).assertDoesNotExist()

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
