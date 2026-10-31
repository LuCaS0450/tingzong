package cn.musicpilgrimage.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** Physical Android multi-pointer input, covering the globe's most failure-prone gesture path. */
@RunWith(AndroidJUnit4::class)
class WorldGestureInputTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun findWeb(view: View): WebView? {
        if (view is WebView && view.url?.startsWith("https://world.tingzong.local/") == true) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun evaluate(web: WebView, expression: String): JSONObject {
        val latch = CountDownLatch(1)
        var result: JSONObject? = null
        instrumentation.runOnMainSync { web.evaluateJavascript("JSON.stringify($expression)") { raw ->
            result = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
            latch.countDown()
        } }
        check(latch.await(8, TimeUnit.SECONDS)) { "World JS callback timed out" }
        return checkNotNull(result) { "World JS was not ready" }
    }
    private fun idle(web: WebView): JSONObject {
        SystemClock.sleep(180) // Wait for injected input to reach the WebView compositor before polling.
        var result = evaluate(web, "worldDiagnostics()")
        repeat(80) { if (!result.optBoolean("moving")) return result; SystemClock.sleep(30); result = evaluate(web, "worldDiagnostics()") }
        error("World camera did not settle")
    }
    private fun event(down: Long, action: Int, points: List<Pair<Float, Float>>) {
        val props = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = .08f } }
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        check(instrumentation.uiAutomation.injectInputEvent(e, true)) { "Touch injection failed" }
        e.recycle()
    }
    private fun pinch(x: Float, y: Float, from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(x-from to y))
        event(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x-from to y, x+from to y))
        for (i in 1..24) { val half = from + (to-from)*i/24; event(down, MotionEvent.ACTION_MOVE, listOf(x-half to y, x+half to y)); SystemClock.sleep(16) }
        repeat(5) { event(down, MotionEvent.ACTION_MOVE, listOf(x-to to y, x+to to y)); SystemClock.sleep(20) }
        event(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x-to to y, x+to to y))
        event(down, MotionEvent.ACTION_UP, listOf(x-to to y))
    }

    @Test fun physicalPinchLocksFocusAndReverseKeepsTheBrowseState() {
        compose.onNodeWithTag("wall-tab-1").performClick()
        var candidate: WebView? = null
        compose.waitUntil(15_000) { instrumentation.runOnMainSync { candidate = findWeb(compose.activity.window.decorView) }; candidate != null }
        val web = checkNotNull(candidate)
        var ready = false
        repeat(60) {
            if (!ready) { ready = runCatching { evaluate(web, "worldDiagnostics()").optBoolean("ready") }.getOrDefault(false); if (!ready) SystemClock.sleep(100) }
        }
        check(ready)
        // The saved location may be arbitrary; use the visible globe control, preserving that location.
        evaluate(web, "(()=>{document.querySelector('[data-zoom=\"0.5\"]').click();return {ok:true};})()")
        idle(web)
        val geometry = evaluate(web, "(()=>{const e=document.getElementById('map'),r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height*.42,localX:e.clientWidth/2,localY:e.clientHeight*.42,logicalWidth:e.clientWidth,width:innerWidth};})()")
        val location = IntArray(2)
        var width = 0
        instrumentation.runOnMainSync { web.getLocationOnScreen(location); width = web.width }
        val scale = width/geometry.getDouble("width")
        val x = (location[0]+geometry.getDouble("x")*scale).toFloat()
        val y = (location[1]+geometry.getDouble("y")*scale).toFloat()
        val localX = geometry.getDouble("localX"); val localY = geometry.getDouble("localY")
        val grabbed = evaluate(web,"worldDiagnostics([$localX,$localY])").getJSONObject("point")
        val logicalScale = width/geometry.getDouble("logicalWidth")
        val down=SystemClock.uptimeMillis()
        event(down,MotionEvent.ACTION_DOWN,listOf(x to y))
        for(i in 1..40){event(down,MotionEvent.ACTION_MOVE,listOf((x+80*logicalScale*i/40).toFloat() to (y+12*logicalScale*i/40).toFloat()));SystemClock.sleep(16)}
        repeat(5){event(down,MotionEvent.ACTION_MOVE,listOf((x+80*logicalScale).toFloat() to (y+12*logicalScale).toFloat()));SystemClock.sleep(20)}
        val dragged = evaluate(web,"worldDiagnostics(null,[${grabbed.getDouble("lng")},${grabbed.getDouble("lat")}])").getJSONObject("anchoredPoint")
        val dragDrift=hypot(dragged.getDouble("x")-localX-80,dragged.getDouble("y")-localY-12)
        event(down,MotionEvent.ACTION_UP,listOf((x+80*logicalScale).toFloat() to (y+12*logicalScale).toFloat()))
        idle(web)
        File(instrumentation.targetContext.filesDir,"world-gesture-review.json").writeText(JSONObject().put("dragFocusDriftPx",dragDrift).toString())
        assertTrue("Grabbed globe coordinate must follow the finger: $dragDrift",dragDrift<3.0)
        val before = evaluate(web, "worldDiagnostics([$localX,$localY])")
        assertTrue("Globe control must be operational before gestures", before.getJSONObject("state").getDouble("zoom") < 1.5)
        val geo = before.getJSONObject("point")
        val lon = geo.getDouble("lng"); val lat = geo.getDouble("lat")
        pinch(x,y,45f,165f)
        idle(web)
        val after = evaluate(web, "worldDiagnostics([$localX,$localY],[$lon,$lat])")
        val projected = after.getJSONObject("anchoredPoint")
        val drift = hypot(projected.getDouble("x")-localX, projected.getDouble("y")-localY)
        val zoomDelta = after.getJSONObject("state").getDouble("zoom")-before.getJSONObject("state").getDouble("zoom")
        val steps = org.json.JSONArray().put(after.getJSONObject("state"))
        var pinches = 1
        var street = after
        repeat(7) {
            if (street.getJSONObject("state").getDouble("zoom") < 14.5) {
                pinch(x,y,45f,165f); street=idle(web);steps.put(street.getJSONObject("state"));pinches++
            }
        }
        File(instrumentation.targetContext.filesDir,"world-gesture-review.json").writeText(JSONObject().put("before",before).put("after",after).put("steps",steps).put("street",street).put("pinchFocusDriftPx",drift).put("zoomDelta",zoomDelta).toString(2))
        assertTrue("Continuous pinch must reach arbitrary streets without selection: ${steps}",street.getJSONObject("state").getDouble("zoom") >= 14.5)
        repeat(pinches-1) { pinch(x,y,165f,45f); idle(web) }
        pinch(x,y,165f,45f)
        idle(web)
        val reverse = evaluate(web, "worldDiagnostics([$localX,$localY],[$lon,$lat])")
        val reversedPoint = reverse.getJSONObject("anchoredPoint")
        val reverseDrift = hypot(reversedPoint.getDouble("x")-localX,reversedPoint.getDouble("y")-localY)
        val record = JSONObject().put("before", before).put("after", after).put("street",street).put("steps",steps).put("reverse", reverse).put("dragFocusDriftPx",dragDrift).put("pinchFocusDriftPx", drift).put("reverseFocusDriftPx",reverseDrift).put("zoomDelta",zoomDelta)
        File(instrumentation.targetContext.filesDir,"world-gesture-review.json").writeText(record.toString(2))
        assertTrue("Real multi-touch must zoom: delta=$zoomDelta", zoomDelta > .8)
        assertTrue("Pinch focus drift: $drift logical pixels", drift < 3.0)
        assertTrue("Inverse pinch must return to globe", reverse.getJSONObject("state").getDouble("zoom") < 1.5)
        assertTrue("Reverse focus drift: $reverseDrift logical pixels",reverseDrift<3.0)
        assertEquals(before.getJSONObject("state").getString("selected"),reverse.getJSONObject("state").getString("selected"))
    }
}
