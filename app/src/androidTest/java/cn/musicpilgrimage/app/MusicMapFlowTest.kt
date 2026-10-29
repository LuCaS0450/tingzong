package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import cn.musicpilgrimage.app.design.MapNetworking
import java.io.IOException

/** Public catalogue browsing only. No fabricated login or backend mutation. */
class MusicMapFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val folder get()=File(instrumentation.targetContext.getExternalFilesDir(null),"music-space-stage6").apply{mkdirs()}
    private fun capture(name:String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync();Thread.sleep(350)
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun findMap(view:View):MapView? {
        if(view is MapView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findMap(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun cityMapPlaceAndSourceReturnRetainSelectionAndCamera() {
        compose.waitUntil(12000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-space-button").performClick()
        compose.onNodeWithTag("room-module-explore").performClick()
        compose.onNodeWithTag("room-city-list").assertIsDisplayed();capture("cities")
        compose.onNodeWithTag("room-city-巴黎").performScrollTo().performClick()
        compose.onNodeWithTag("room-map-wall").assertIsDisplayed()
        val view=AtomicReference<MapView?>();val map=AtomicReference<MapLibreMap?>()
        compose.runOnIdle{view.set(findMap(compose.activity.window.decorView));view.get()?.getMapAsync{map.set(it)}}
        var rendered=false
        val deadline=System.currentTimeMillis()+40000
        while(System.currentTimeMillis()<deadline&&!rendered) {
            instrumentation.runOnMainSync {
                val native=map.get();val v=view.get()
                if(native?.style?.isFullyLoaded==true&&v!=null) {
                    val lines=native.style!!.layers.filterIsInstance<LineLayer>().map{it.id}
                    rendered=lines.isNotEmpty()&&native.queryRenderedFeatures(RectF(0f,0f,v.width.toFloat(),v.height.toFloat()),*lines.toTypedArray()).isNotEmpty()
                }
            }
            if(!rendered)Thread.sleep(250)
        }
        File(folder,"map-rendering.json").writeText(JSONObject().put("streetsRendered",rendered).put("liveConfiguredKey",BuildConfig.MAPTILER_KEY.isNotBlank()).toString())
        if(rendered)compose.waitUntil(30000){compose.onAllNodesWithTag("room-map-ready").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("MapTiler",substring=true).assertCountEquals(0)
        compose.onAllNodesWithText("OpenStreetMap",substring=true).assertCountEquals(0)
        if(rendered) {
            var names=false
            compose.waitUntil(20000){
                instrumentation.runOnMainSync {
                    val native=map.get()!!;val v=view.get()!!
                    names=native.queryRenderedFeatures(RectF(0f,0f,v.width.toFloat(),v.height.toFloat()),"City labels","Town labels","Place labels").isNotEmpty()
                }
                names
            }
        }
        if(rendered)instrumentation.runOnMainSync {
            assertFalse(map.get()!!.uiSettings.isLogoEnabled);assertFalse(map.get()!!.uiSettings.isAttributionEnabled)
            val layers=JSONObject(map.get()!!.style!!.json).getJSONArray("layers")
            val definitions=org.json.JSONArray()
            for(i in 0 until layers.length())layers.getJSONObject(i).let{row->row.optJSONObject("layout")?.opt("text-field")?.let{text->definitions.put(JSONObject().put("id",row.optString("id")).put("text",text))}}
            File(folder,"map-label-fields.json").writeText(definitions.toString())
        }
        compose.onNodeWithTag("room-map-place-list").performClick()
        // Select a reviewed MV location through its accessible list, also usable with failed tiles.
        compose.onNodeWithTag("room-map-place-samaritaine").performClick()
        compose.onNodeWithTag("room-place-card").assertIsDisplayed();capture("wall-map")
        compose.onNodeWithTag("room-map-expand").performClick()
        compose.onNodeWithTag("room-map-full").assertIsDisplayed()
        if(rendered)compose.waitUntil(30000){compose.onAllNodesWithTag("room-map-ready").fetchSemanticsNodes().isNotEmpty()}
        val before=AtomicReference<List<Double>>()
        if(rendered) {
            compose.onNodeWithTag("room-map-zoom-in").performClick()
            compose.onNodeWithTag("room-map-full").performTouchInput{swipeLeft(durationMillis=350)}
            compose.waitForIdle();Thread.sleep(400)
            instrumentation.runOnMainSync{map.get()!!.cameraPosition.let{before.set(listOf(it.target!!.latitude,it.target!!.longitude,it.zoom))}}
        }
        capture("full-map")
        if(rendered)instrumentation.runOnMainSync{map.get()!!.cancelTransitions();map.get()!!.cameraPosition.let{before.set(listOf(it.target!!.latitude,it.target!!.longitude,it.zoom))}}
        compose.onNodeWithTag("room-place-open").performClick()
        compose.onNodeWithTag("room-place-detail").assertIsDisplayed();capture("place-detail")
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-map-full").assertIsDisplayed()
        compose.onNodeWithTag("room-place-card").assertIsDisplayed()
        if(rendered) {
            val restored=AtomicReference<MapLibreMap>()
            compose.runOnIdle{findMap(compose.activity.window.decorView)!!.getMapAsync{restored.set(it)}}
            compose.waitUntil(25000){compose.onAllNodesWithTag("room-map-ready").fetchSemanticsNodes().isNotEmpty()}
            instrumentation.runOnMainSync{restored.get().cameraPosition.let{assertEquals(before.get()[0],it.target!!.latitude,.0001);assertEquals(before.get()[1],it.target!!.longitude,.0001);assertEquals(before.get()[2],it.zoom,.01)}}
        }
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-map-wall").assertIsDisplayed()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-city-巴黎").assertIsSelected()
    }
    @Test fun failedMapStillAllowsReviewedPlaceListAndDetail() {
        val original=MapNetworking.client
        MapNetworking.client=original.newBuilder().addInterceptor{throw IOException("isolated offline check")}.build()
        try {
            compose.waitUntil(12000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("room-space-button").performClick();compose.onNodeWithTag("room-module-explore").performClick()
            compose.onNodeWithTag("room-city-巴黎").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("地图暂时未能加载，请检查网络或地图配置。").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("room-map-place-list").performClick();compose.onNodeWithTag("room-map-place-samaritaine").performClick()
            compose.onNodeWithTag("room-place-card").assertIsDisplayed();capture("map-unavailable")
            compose.onNodeWithTag("room-place-open").performClick();compose.onNodeWithTag("room-place-detail").assertIsDisplayed()
            compose.onNodeWithTag("room-back").performClick();compose.onNodeWithTag("room-place-card").assertIsDisplayed()
        } finally {MapNetworking.client=original;instrumentation.runOnMainSync{MapNetworking.configure(instrumentation.targetContext)}}
    }
}
